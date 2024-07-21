/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.drftpd.slave.vfs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.common.io.PhysicalFile;
import org.drftpd.slave.Slave;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * @author mog
 * @version $Id$
 */
public class RootCollection {
    private static final Logger logger = LogManager.getLogger(RootCollection.class);

    private ArrayList<Root> _roots;
    private Slave _slave;
    private ThreadPoolExecutor _pool;
    private ArrayList<Pattern> pathsToIgnore;

    public RootCollection(Slave slave, Collection<Root> roots) throws IOException {
        /** sanity checks * */
        validateRoots(roots);
        _roots = new ArrayList<>(roots);
        _slave = slave;
        int numThreads = _roots.size() * _slave.rootCollectionThreads();
        logger.debug("Initializing the pool with {} threads", numThreads);
        _pool = new ThreadPoolExecutor(1, numThreads, 300, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), new RootListHandlerThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        _pool.allowCoreThreadTimeOut(true);

        pathsToIgnore = new ArrayList<Pattern>();
        Properties p = slave.getConfig();
        for (int i = 1; ; i++) {
            String pattern = p.getProperty("slave.pathstoignore." + i);
            if (pattern == null)
                break;

            try {
                pathsToIgnore.add(Pattern.compile(pattern));
                logger.debug("Added ignore pattern " + pattern);
            }
            catch (PatternSyntaxException e) {
                logger.error("Error compiling regex pattern for slave.pathstoignore." + i + ": " + pattern, e);
            }
        }
    }

    private static void validateRoots(Collection<Root> roots) throws IOException {
        File[] mountsArr = File.listRoots();
        ArrayList<File> mounts = new ArrayList<>(mountsArr.length);

        for (File aMountsArr : mountsArr) {
            mounts.add(aMountsArr);
        }

        mounts.sort(new Comparator<>() {
            public boolean equals(Object obj) {
                if (obj == null) return false;
                return obj.getClass() == getClass();
            }

            public int hashCode() {
                return getClass().hashCode();
            }

            public int compare(File o1, File o2) {
                int thisVal = o1.getPath().length();
                int anotherVal = o2.getPath().length();

                return (Integer.compare(anotherVal, thisVal));
            }
        });

        for (Root root : roots) {

            File rootFile = root.getFile();

            if (!rootFile.exists()) {
                if (!rootFile.mkdirs()) {
                    throw new IOException("mkdirs() failed on "
                            + rootFile.getPath());
                }
            }

            if (!rootFile.exists()) {
                throw new FileNotFoundException("Invalid root: " + rootFile);
            }

            String fullpath = rootFile.getAbsolutePath();

            Hashtable<String, Object> usedMounts = new Hashtable<>();

            for (File mount : mounts) {

                if (fullpath.startsWith(mount.getPath())) {
                    logger.info("{} in mount {}", fullpath, mount.getPath());

                    if (usedMounts.get(mount.getPath()) != null) {
                        throw new IOException("Multiple roots in mount "
                                + mount.getPath());
                    }

                    usedMounts.put(mount.getPath(), new Object());

                    break;
                }
            }

            for (Root root2 : roots) {

                if (root == root2) {
                    continue;
                }

                if ((root2.getPath() + File.pathSeparator).startsWith(root
                        .getPath()
                        + File.pathSeparator)) {
                    throw new RuntimeException("Overlapping roots: "
                            + root.getPath() + " and " + root2.getPath());
                }
            }
        }
    }

    private boolean ignorePath(String path) {
        for (Pattern pattern : pathsToIgnore) {
            if (pattern.matcher(path).matches()) {
                logger.trace("Ignoring " + path);
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a sorted (alphabetical) list of inodes in the path given
     *
     * @param path The path to get file listing off
     * @param concurrent Whether we should concurrently work on all roots
     * @return
     */
    public TreeSet<String> getLocalInodes(String path, boolean concurrent) {
        TreeSet<String> files = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (concurrent) {
            CountDownLatch latch = new CountDownLatch(_roots.size());
            String[][] rootFiles = new String[_roots.size()][];
            for (int i = 0; i < _roots.size(); i++) {
                _pool.execute(new RootListHandler(rootFiles, i, latch, path));
            }
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException e) {
                    // Loop around and wait again
                }
            }
            for (int i = 0; i < _roots.size(); i++) {
                if (rootFiles[i] != null) {
                    if (!pathsToIgnore.isEmpty()) {
                        for (String file : rootFiles[i]) {
                            if (!ignorePath(path + File.separatorChar + file)) {
                                files.add(file);
                            }
                        }
                    } else {
                        files.addAll(Arrays.asList(rootFiles[i]));
                    }
                }
            }
        } else {
            for (int i = 0; i < _roots.size(); i++) {
                File rootPath = _roots.get(i).getFile(path);
                String[] rootFiles = rootPath.list();
                if (rootFiles != null) {
                    if (!pathsToIgnore.isEmpty()) {
                        for (String file : rootFiles) {
                            if (!ignorePath(path + File.separatorChar + file)) {
                                files.add(file);
                            }
                        }
                    } else {
                        files.addAll(Arrays.asList(rootFiles));
                    }
                }
            }
        }
        return files;
    }

    public long getLastModifiedForPath(String path) {
        long lastModified = Long.MIN_VALUE;
        for (Root root : _roots) {
            // This will return 0L if the path does not exist on given root
            long rootLastModified = root.getFile(path).lastModified();
            if (rootLastModified > lastModified) {
                lastModified = rootLastModified;
            }
        }
        return lastModified;
    }

    public Root getARoot() {
        long mostFree = 0;
        Root mostFreeRoot = null;

        for (Root root : _roots) {
            long diskSpaceAvailable = root.getDiskSpaceAvailable();

            if (diskSpaceAvailable > mostFree) {
                mostFree = diskSpaceAvailable;
                mostFreeRoot = root;
            }
        }

        if (mostFreeRoot == null) {
            throw new RuntimeException("NoAvailableRootsException");
        }

        return mostFreeRoot;
    }

    /**
     * Get a directory specified by dir under an approperiate root for storing
     * storing files in.
     *
     * @throws IOException
     */
    public File getARootFileDir(String dir) throws IOException {
        Root bestRoot = _slave.getDiskSelection().getBestRoot(dir);

        // to avoid this error SlaveSelectionManager MUST work
        // synchronized with DiskSelection.
        if (bestRoot == null) {
            throw new IOException("No suitable root was found.");
        }
        bestRoot.touch();

        PhysicalFile file = bestRoot.getFile(dir);
        file.mkdirs2();

        return file;
    }

    // Get root which has most of the tree structure that we have.
    public PhysicalFile getFile(String path) throws FileNotFoundException {
        return new PhysicalFile(getRootForFile(path).getPath() + File.separatorChar + path);
    }

    public List<File> getMultipleFiles(String path) throws FileNotFoundException {
        ArrayList<File> files = new ArrayList<>();

        for (Root r : getMultipleRootsForFile(path)) {
            files.add(r.getFile(path));
        }
        return files;
    }

    public List<Root> getMultipleRootsForFile(String path)
            throws FileNotFoundException {
        ArrayList<Root> roots = new ArrayList<>();


        for (Root r : _roots) {
            if (r.getFile(path).exists()) {
                roots.add(r);
            }
        }
        for (Root root : _roots) {

            if (root.getFile(path).exists()) {
                roots.add(root);
            }
        }

        if (roots.size() == 0) {
            throw new FileNotFoundException("Unable to find suitable root: "
                    + path);
        }

        return roots;
    }

    public Root getRootForFile(String path) throws FileNotFoundException {
        for (Root root : _roots) {
            File file = new File(root.getPath() + PhysicalFile.separatorChar + path);
            if (file.exists()) {
                return root;
            }
        }
        throw new FileNotFoundException(path + " wasn't found in any root");
    }

    public long getTotalDiskSpaceAvailable() {
        long totalDiskSpaceAvailable = 0;

        for (Root root : _roots) {
            totalDiskSpaceAvailable += root.getDiskSpaceAvailable();
        }

        return totalDiskSpaceAvailable;
    }

    public long getTotalDiskSpaceCapacity() {
        long totalDiskSpaceCapacity = 0;

        for (Root root : _roots) {
            totalDiskSpaceCapacity += root.getDiskSpaceCapacity();
        }

        return totalDiskSpaceCapacity;
    }

    public Iterator<Root> iterator() {
        return _roots.iterator();
    }

    public ArrayList<Root> getRootList() {
        return _roots;
    }

    private class RootListHandler implements Runnable {

        private final String[][] _files;
        private final int _root;
        private final CountDownLatch _latch;
        private final String _path;

        public RootListHandler(String[][] files, int root, CountDownLatch latch, String path) {
            _files = files;
            _root = root;
            _latch = latch;
            _path = path;
        }

        public void run() {
            Thread currThread = Thread.currentThread();
            currThread.setName("Root List Handler[" + currThread.getId() + "] " +
                    "- processing root " + _root + " - " + _path);
            File rootPath = _roots.get(_root).getFile(_path);
            if (rootPath.exists()) {
                _files[_root] = rootPath.list();
            }
            _latch.countDown();
            currThread.setName(RootListHandlerThreadFactory.getIdleThreadName(currThread.getId()));
        }
    }
}

class RootListHandlerThreadFactory implements ThreadFactory {
    public static String getIdleThreadName(long threadId) {
        return "Root List Handler[" + threadId + "] - Waiting for root to process";
    }

    public Thread newThread(Runnable r) {
        Thread t = Executors.defaultThreadFactory().newThread(r);
        t.setName(getIdleThreadName(t.getId()));
        return t;
    }
}
