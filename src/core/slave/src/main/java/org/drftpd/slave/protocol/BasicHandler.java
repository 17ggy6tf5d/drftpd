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
package org.drftpd.slave.protocol;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.drftpd.common.exceptions.AsyncResponseException;
import org.drftpd.common.exceptions.TransferDeniedException;
import org.drftpd.common.io.PermissionDeniedException;
import org.drftpd.common.io.PhysicalFile;
import org.drftpd.common.network.AsyncCommandArgument;
import org.drftpd.common.network.AsyncResponse;
import org.drftpd.common.network.PassiveConnection;
import org.drftpd.common.slave.ConnectInfo;
import org.drftpd.common.slave.LightRemoteInode;
import org.drftpd.common.slave.TransferIndex;
import org.drftpd.common.slave.TransferStatus;
import org.drftpd.slave.Slave;
import org.drftpd.slave.network.*;
import org.drftpd.slave.vfs.RootCollection;
import org.drftpd.slave.vfs.Root;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Basic operations handling.
 *
 * @author fr0w
 * @author zubov
 * @author mog
 * @version $Id$
 */
public class BasicHandler extends AbstractHandler {
    private static final Logger logger = LogManager.getLogger(BasicHandler.class);

    // The following variables are static as they are used to signal between
    // remerging and the pause/resume functions, due to the way the handler
    // map works these are run against separate object instances.
    private static final AtomicBoolean remergePaused = new AtomicBoolean();
    private static final AtomicBoolean _remerging = new AtomicBoolean();
    private final ThreadPoolExecutor _pool;
    private static final Object remergeWaitObj = new Object();
    private static final Object mergeDepthWaitObj = new Object();
    private final ArrayList<String> mergeDepth = new ArrayList<>();
    private final ArrayList<RemergeItem> remergeResponses = new ArrayList<>();

    public BasicHandler(SlaveProtocolCentral central) {
        super(central);

        // Initialize us as not remerging
        _remerging.set(false);

        // Get the amount of concurrent threads our threadpool can run at
        // We start with 1 as a default and only increase if we are running threaded remerge mode
        int numThreads = 1;
        if (getSlaveObject().threadedRemerge()) {
            logger.fatal("threaded remerge enabled");
            if (getSlaveObject().threadedThreads() > 0) {
                numThreads = getSlaveObject().threadedThreads();
            } else {
                numThreads = Runtime.getRuntime().availableProcessors();
                if (numThreads > 2) {
                    numThreads -= 1;
                }
            }
        }
        logger.debug("Initializing the pool for remerge with {} threads", numThreads);
        _pool = new ThreadPoolExecutor(numThreads, numThreads, 300, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), new RemergeThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        _pool.allowCoreThreadTimeOut(true);
    }

    @Override
    public String getProtocolName() {
        return "BasicProtocol";
    }

    // TODO check this.
    // ABORT
    public AsyncResponse handleAbort(AsyncCommandArgument ac) {
        TransferIndex ti = new TransferIndex(Integer.parseInt(ac.getArgsArray()[0]));

        Map<TransferIndex, Transfer> transfers = getSlaveObject().getTransferMap();

        if (!transfers.containsKey(ti)) {
            return null;
        }

        Transfer t = transfers.get(ti);
        t.abort(ac.getArgsArray()[1]);
        return new AsyncResponse(ac.getIndex());
    }

    // CONNECT
    public AsyncResponse handleConnect(AsyncCommandArgument ac) {
        String[] data = ac.getArgsArray()[0].split(":");
        boolean encrypted = ac.getArgsArray()[1].equals("true");
        boolean useSSLClientHandshake = ac.getArgsArray()[2].equals("true");
        InetAddress address;

        try {
            address = InetAddress.getByName(data[0]);
        } catch (UnknownHostException e1) {
            return new AsyncResponseException(ac.getIndex(), e1);
        }

        int port = Integer.parseInt(data[1]);
        Transfer t = new Transfer(new ActiveConnection(encrypted ? getSlaveObject().getSSLContext() : null,
                new InetSocketAddress(address, port), useSSLClientHandshake, getSlaveObject().getBindIP()),
                getSlaveObject(), new TransferIndex());

        getSlaveObject().addTransfer(t);

        return new AsyncResponseTransfer(ac.getIndex(), new ConnectInfo(port, t.getTransferIndex(), t.getTransferStatus()));
    }

    // DELETE
    public AsyncResponse handleDelete(AsyncCommandArgument ac) {
        try {
            getSlaveObject().delete(ac.getArgs());
            sendResponse(new AsyncResponseDiskStatus(getSlaveObject().getDiskStatus()));
            return new AsyncResponse(ac.getIndex());
        } catch (IOException e) {
            return new AsyncResponseException(ac.getIndex(), e);
        }
    }

    // LISTEN
    public AsyncResponse handleListen(AsyncCommandArgument ac) {
        String[] data = ac.getArgs().split(":");
        boolean encrypted = data[0].equals("true");
        boolean useSSLClientMode = data[1].equals("true");
        PassiveConnection c;

        try {
            c = new PassiveConnection(encrypted ? getSlaveObject().getSSLContext() : null,
                    getSlaveObject().getPortRange(), useSSLClientMode, getSlaveObject().getBindIP());

        } catch (IOException e) {
            return new AsyncResponseException(ac.getIndex(), e);
        }

        Transfer t = new Transfer(c, getSlaveObject(), new TransferIndex());
        getSlaveObject().addTransfer(t);

        return new AsyncResponseTransfer(ac.getIndex(), new ConnectInfo(c.getLocalPort(), t.getTransferIndex(), t.getTransferStatus()));
    }

    // MAXPATH
    public AsyncResponse handleMaxpath(AsyncCommandArgument ac) {
        int maxPathLength = getSlaveObject().getMaxPathLength();
        return new AsyncResponseMaxPath(ac.getIndex(), maxPathLength);
    }

    // PING
    public AsyncResponse handlePing(AsyncCommandArgument ac) {
        return new AsyncResponse(ac.getIndex());
    }

    // RECEIVE
    public AsyncResponse handleReceive(AsyncCommandArgument ac) {
        char type = ac.getArgsArray()[0].charAt(0);
        long position = Long.parseLong(ac.getArgsArray()[1]);
        TransferIndex transferIndex = new TransferIndex(Integer.parseInt(ac.getArgsArray()[2]));
        String inetAddress = ac.getArgsArray()[3];
        String path = ac.getArgsArray()[4];
        String fileName = path.substring(path.lastIndexOf("/") + 1);
        String dirName = path.substring(0, path.lastIndexOf("/"));
        long minSpeed = Long.parseLong(ac.getArgsArray()[5]);
        long maxSpeed = Long.parseLong(ac.getArgsArray()[6]);
        Transfer t = getSlaveObject().getTransfer(transferIndex);
        t.setMinSpeed(minSpeed);
        t.setMaxSpeed(maxSpeed);
        getSlaveObject().sendResponse(new AsyncResponse(ac.getIndex())); // return calling thread on master
        try {
            return new AsyncResponseTransferStatus(t.receiveFile(dirName, type, fileName, position, inetAddress));
        } catch (IOException | TransferDeniedException e) {
            return new AsyncResponseTransferStatus(new TransferStatus(transferIndex, e));
        }
    }

    // REMERGE PAUSE
    public AsyncResponse handleRemergePause(AsyncCommandArgument ac) {
        remergePaused.set(true);
        return new AsyncResponse(ac.getIndex());
    }

    // REMERGE RESUME
    public AsyncResponse handleRemergeResume(AsyncCommandArgument ac) {
        remergePaused.set(false);
        synchronized (remergeWaitObj) {
            remergeWaitObj.notifyAll();
        }
        return new AsyncResponse(ac.getIndex());
    }

    /* REMERGE
     * args array:
     * 0: path (string)
     * 1: partialRemerge (boolean)
     * 2: skipAgeCutoff (long)
     * 3: masterTime (long)
     * 4: instantOnline (boolean)
     */
    public AsyncResponse handleRemerge(AsyncCommandArgument ac) {
        // Slave Protocol central calls this with a dedicated thread which we give the lowest possible priority
        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);

        if (!_remerging.compareAndSet(false, true))
        {
            logger.warn("Received remerge request while we are remerging");
            return new AsyncResponseException(ac.getIndex(), new Exception("Already remerging"));
        }
        try {
            logger.debug("Remerging start");

            WalkFileTree wft = new WalkFileTree(getSlaveObject());
            var roots = getSlaveObject().getRoots().getRootList();
            for (Root root : roots) {
                wft.Walk(root.getPath());
            }
            List<AsyncResponseRemerge> rrs = wft.getWalkResult();
            Slave slave = getSlaveObject();
            for (var rr : rrs) {
                if (!slave.isOnline()) {
                    // Slave has shut down, no need to continue with remerge
                    return null;
                }

                while (remergePaused.get() && slave.isOnline()) {
                    logger.debug("Remerging paused, sleeping");
                    synchronized (remergeWaitObj) {
                        try {
                            remergeWaitObj.wait(1000);
                        } catch (InterruptedException e) {
                            // Either we have been woken properly in which case we will exit the
                            // loop or we have not in which case we will wait again.
                        }
                    }
                }

                logger.debug("Sending {} to the master", rr.getPath());
                sendResponse(rr);
            }

            logger.debug("Remerging done");
            return new AsyncResponse(ac.getIndex());
/*            
            // Get the arguments for this command
            String[] argsArray = ac.getArgsArray();
            String basePath = argsArray[0];
            boolean instantOnline = Boolean.parseBoolean(argsArray[4]);
            boolean partialRemerge = Boolean.parseBoolean(argsArray[1]) && !getSlaveObject().ignorePartialRemerge() && !instantOnline;
            long skipAgeCutoff = 0L; // We only care for the value the master gave us if we are partial remerging (see below)
            long masterTime = Long.parseLong(argsArray[3]);

            // Based on the input decide the remerge situation on our end and report back
            String remergeDecision = "Unexpected situation encountered in handleRemerge, please report";
            if (partialRemerge) {
                skipAgeCutoff = Long.parseLong(argsArray[2]);

                if (skipAgeCutoff != Long.MIN_VALUE) {
                    skipAgeCutoff += System.currentTimeMillis() - masterTime;
                }
                Date cutoffDate = new Date(skipAgeCutoff);
                remergeDecision = "Instant online: disabled, Partial remerge: enabled. skipping all files last modified before " + cutoffDate.toString() + ".  Remerging with " + _pool.getMaximumPoolSize() + " threads";
            } else if (instantOnline) {
                remergeDecision = "Instant online: enabled, Partial remerge: disabled. Remerging in background with " + _pool.getMaximumPoolSize() + " threads";
            } else {
                remergeDecision = "Instant online: disabled, Partial remerge: disabled. Remerging in foreground with " + _pool.getMaximumPoolSize() + " threads";
            }
            logger.info(remergeDecision);
            sendResponse(new AsyncResponseSiteBotMessage(remergeDecision));

            _remerging.set(true);
            // Start with a empty list!
            mergeDepth.clear();
            logger.debug("Remerging started");
            _pool.execute(new HandleRemergeThread(getSlaveObject().getRoots(), basePath, partialRemerge, skipAgeCutoff));

            // Keep track of last remerge message being send
            long lastRemergeMessageSend = System.currentTimeMillis();
            long reportIdle = 30000L;

            while (_pool.getActiveCount() > 0 || remergeResponses.size() > 0) {
                // First check if we are still online, bail if not
                if (!getSlaveObject().isOnline()) {
                    // Slave has shut down, no need to continue with remerge
                    return null;
                }

                // Handle being idle for a long period
                if ((System.currentTimeMillis() - lastRemergeMessageSend) >= reportIdle) {
                    logger.warn("We have pending items in our queue but have not sent anything for 30 seconds");
                    logger.warn("Queue: {}, Active threads: {}, Pending responses: {}",
                            _pool.getQueue().size(), _pool.getActiveCount(), remergeResponses.size());
                    synchronized (remergeResponses) {
                        for (RemergeItem ri : remergeResponses) {
                            logger.debug("Path [{}] in queue", ri.getAsyncResponseRemerge().getPath());
                        }
                    }
                    // Double the report interval
                    reportIdle *= 2;
                } else {
                    // reset reportIdle if we continue
                    reportIdle = 30000L;
                }

                // Check if we can send stuff to the master
                int sentResponses = 0;
                synchronized(remergeResponses) {
                    ListIterator<RemergeItem> rrIterator = remergeResponses.listIterator(remergeResponses.size());
                    while(rrIterator.hasPrevious()) {
                        RemergeItem ri = rrIterator.previous();
                        logger.debug("Remerge item [{}] with depth directories: [{}]", ri.getAsyncResponseRemerge().getPath(), Arrays.asList(ri.getDepthDirectories()));
                        synchronized (mergeDepthWaitObj) {
                            if (mergeDepth.containsAll(ri.getDepthDirectories())) {
                                logger.debug("Sending {} to the master", ri.getAsyncResponseRemerge().getPath());
                                rrIterator.remove();
                                sendResponse(ri.getAsyncResponseRemerge());
                                lastRemergeMessageSend = System.currentTimeMillis();
                                updateDepth(ri.getAsyncResponseRemerge().getPath() + "/");
                                sentResponses += 1;
                            }
                        }
                    }
                }

                // Do not do a busy loop when we are not sending anything to master, better to sleep for 0.2 second
                logger.debug("We have sent {} respponses to master", sentResponses);
                if (sentResponses <= 1) {
                    try {
                        logger.debug("Queue: {}, Active threads: {}, Pending responses: {}, sleeping 0.2 second",
                                _pool.getQueue().size(), _pool.getActiveCount(), remergeResponses.size());
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        // Either we have been woken properly in which case we will exit the
                        // loop or we have not in which case we will wait again.
                    }
                }
            }

            logger.debug("Remerging done");
            _remerging.set(false);
            // Make sure we do not hog memory and clear the list
            mergeDepth.clear();
            return new AsyncResponse(ac.getIndex());
*/
        } catch (Throwable e) {
            logger.error("Exception during merging", e);
            sendResponse(new AsyncResponseSiteBotMessage("Exception during merging"));

            return new AsyncResponseException(ac.getIndex(), e);
        } finally {
            _remerging.set(false);
        }
    }

    // RENAME
    public AsyncResponse handleRename(AsyncCommandArgument ac) {
        String from = ac.getArgsArray()[0];
        String toDir = ac.getArgsArray()[1];
        String toFile = ac.getArgsArray()[2];

        try {
            getSlaveObject().rename(from, toDir, toFile);
            return new AsyncResponse(ac.getIndex());
        } catch (IOException e) {
            return new AsyncResponseException(ac.getIndex(), e);
        }
    }

    // SEND
    public AsyncResponse handleSend(AsyncCommandArgument ac) {
        char type = ac.getArgsArray()[0].charAt(0);
        long position = Long.parseLong(ac.getArgsArray()[1]);
        TransferIndex transferIndex = new TransferIndex(Integer.parseInt(ac.getArgsArray()[2]));
        String inetAddress = ac.getArgsArray()[3];
        String path = ac.getArgsArray()[4];
        long minSpeed = Long.parseLong(ac.getArgsArray()[5]);
        long maxSpeed = Long.parseLong(ac.getArgsArray()[6]);
        Transfer t = getSlaveObject().getTransfer(transferIndex);
        t.setMinSpeed(minSpeed);
        t.setMaxSpeed(maxSpeed);
        sendResponse(new AsyncResponse(ac.getIndex()));

        // calling thread on master
        try {
            return new AsyncResponseTransferStatus(t.sendFile(path, type, position, inetAddress));
        } catch (IOException | TransferDeniedException e) {
            return new AsyncResponseTransferStatus(new TransferStatus(t
                    .getTransferIndex(), e));
        }
    }

    // CHECKSUM
    public AsyncResponse handleChecksum(AsyncCommandArgument ac) {
        try {
            return new AsyncResponseChecksum(ac.getIndex(), getSlaveObject().checkSum(ac.getArgs()));
        } catch (IOException e) {
            return new AsyncResponseException(ac.getIndex(), e);
        }
    }

    // SHUTDOWN
    public AsyncResponse handleShutdown(AsyncCommandArgument ac) {
        logger.info("The master has requested that I shutdown");
        getSlaveObject().shutdown();
        System.exit(0);
        return null;
    }

    // CHECK SSL
    public AsyncResponse handleCheckSSL(AsyncCommandArgument ac) {
        return new AsyncResponseSSLCheck(ac.getIndex(), getSlaveObject().getSSLContext() != null);
    }

    private void updateDepth(String path) {
        // Hack to ensure we do not use double //...
        if (path.equalsIgnoreCase("//")) {
            path = "/";
        }

        synchronized (mergeDepthWaitObj) {
            if (!mergeDepth.contains(path)) {
                logger.debug("updateDepth - Adding [{}]", path);
                mergeDepth.add(path);
            }
        }
    }

    private class HandleRemergeThread implements Runnable {
        private RootCollection _rootCollection = null;
        private String _path = null;
        private boolean _partialRemerge = false;
        private long _skipAgeCutoff = 0L;

        public HandleRemergeThread(RootCollection rootCollection, String path, boolean partialRemerge, long skipAgeCutoff) {
            this._rootCollection = rootCollection;
            this._path = path;
            this._partialRemerge = partialRemerge;
            this._skipAgeCutoff = skipAgeCutoff;
        }

        public void run() {
            Thread currThread = Thread.currentThread();

            // Sanity check
            if (!getSlaveObject().isOnline()) {
                // Slave has shut down, no need to continue with remerge
                return;
            }

            // Give the thread a logical name
            currThread.setName("Remerge Thread["+ currThread.getId() + "] - Processing " + _path);
            while (remergePaused.get() && getSlaveObject().isOnline()) {
                logger.debug("Remerging paused, sleeping");
                synchronized (remergeWaitObj) {
                    try {
                        remergeWaitObj.wait(1000);
                    } catch (InterruptedException e) {
                        // Either we have been woken properly in which case we will exit the
                        // loop or we have not in which case we will wait again.
                    }
                }
            }

            // Get a list of contents
            Set<String> inodes = _rootCollection.getLocalInodes(_path, getSlaveObject().concurrentRootIteration());
            List<LightRemoteInode> fileList = new ArrayList<LightRemoteInode>();
            ArrayList<String> dirList = new ArrayList<>();

            boolean inodesModified = false;
            long pathLastModified = _rootCollection.getLastModifiedForPath(_path);
            // Need to check the last modified of the parent itself to detect where
            // files have been deleted but none changed or added
            if (_partialRemerge && pathLastModified > _skipAgeCutoff) {
                inodesModified = true;
            }
            for (String inode : inodes) {
                String fullPath = _path + "/" + inode;
                if (_path.endsWith("/")) {
                    fullPath = _path + inode;
                }
                PhysicalFile file;
                try {
                    file = _rootCollection.getFile(fullPath);
                } catch (FileNotFoundException e) {
                    // something is screwy, we just found the file, it has to exist
                    // race condition i guess, stop deleting files outside drftpd!
                    logger.error("Error getting file {} even though we just listed it, check permissions", fullPath, e);
                    sendResponse(new AsyncResponseSiteBotMessage("Error getting file " + fullPath + " check permissions"));
                    continue;
                }
                try {
                    if (file.isSymbolicLink()) {
                        // ignore it, but log an error
                        logger.warn("You have a symbolic link {} -- these are ignored by drftpd", fullPath);
                        sendResponse(new AsyncResponseSiteBotMessage("You have a symbolic link " + fullPath + " -- these are ignored by drftpd"));
                        continue;
                    }
                } catch (IOException e) {
                    logger.warn("You have a symbolic link that couldn't be read at {} -- these are ignored by drftpd", fullPath);
                    sendResponse(new AsyncResponseSiteBotMessage("You have a symbolic link that couldn't be read at " + fullPath + " -- these are ignored by drftpd"));
                    continue;
                }
                if (_partialRemerge && file.lastModified() > _skipAgeCutoff) {
                    inodesModified = true;
                }
                if (file.isDirectory()) {
                    dirList.add(fullPath + "/");
                    _pool.execute(new HandleRemergeThread(_rootCollection, fullPath, _partialRemerge, _skipAgeCutoff));
                }
                fileList.add(new LightRemoteInode(file));
            }
            if (!_partialRemerge || inodesModified) {
                AsyncResponseRemerge arr = new AsyncResponseRemerge(_path, fileList, pathLastModified);
                if (dirList.size() == 0) {
                    logger.debug("Sending {} to the master, bypassing queue as no depth directories", _path);
                    sendResponse(arr);
                    updateDepth(_path + "/");
                } else {
                    synchronized (remergeResponses) {
                        remergeResponses.add(new RemergeItem(dirList, arr));
                    }
                }
            } else {
                updateDepth(_path + "/");
                logger.debug("Skipping send of {} as no files changed since last merge", _path);
            }
            currThread.setName(RemergeThreadFactory.getIdleThreadName(currThread.getId()));
        }
    }

    static class RemergeThreadFactory implements ThreadFactory {
        public static String getIdleThreadName(long threadId) {
            return "Remerge Thread[" + threadId + "] - Waiting for path to process";
        }

        public Thread newThread(Runnable r) {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setPriority(Thread.MIN_PRIORITY);
            t.setName(getIdleThreadName(t.getId()));
            return t;
        }
    }

    static class RemergeItem {
        private final List<String> _dd;
        private final AsyncResponseRemerge _arr;

        public RemergeItem(List<String> dd, AsyncResponseRemerge arr) {
            _dd = dd;
            _arr = arr;
        }

        public List<String> getDepthDirectories() {
            return _dd;
        }

        public AsyncResponseRemerge getAsyncResponseRemerge() {
            return _arr;
        }
    }

    public class WalkFileTree extends SimpleFileVisitor<Path>
    {
        private final Slave _slave;
        private List<Pattern> _directoryPathsToIgnore;
        private List<Pattern> _filePathsToIgnore;

        private List<Pattern> CompileRegExPatterns(List<String> patterns) {
            var result = new ArrayList<Pattern>();

            for (String pattern : patterns)
            {
                if (pattern == null)
                    continue;

                try {
                    result.add(Pattern.compile(pattern));
                }
                catch (PatternSyntaxException e) {
                    logger.error("Error compiling regex pattern: " + pattern, e);
                }
            }

            return result;
        }

        public WalkFileTree(Slave slave)
        {
            _slave = slave;
            Properties properties = slave.getConfig();

            List<String> directoryPathsToIgnore = new ArrayList<String>();
            List<String> filePathsToIgnore = new ArrayList<String>();

            for (int i = 1; ; i++) {
                String pattern = properties.getProperty("slave.pathstoignore." + i);
                String type = properties.getProperty("slave.pathstoignore." + i + ".type");
                if (pattern == null)
                    break;
    
                if ("directory".equalsIgnoreCase(type)) {
                    directoryPathsToIgnore.add(pattern);
                }
                else if ("file".equalsIgnoreCase(type)) {
                    filePathsToIgnore.add(pattern);
                }
            }

            _directoryPathsToIgnore = CompileRegExPatterns(directoryPathsToIgnore);
            _filePathsToIgnore = CompileRegExPatterns(filePathsToIgnore);
        }

        private boolean ignorePath(List<Pattern> patterns, String path) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(path).matches()) {
                    return true;
                }
            }
            return false;
        }

        private boolean ignoreDirectory(String path) {
            return ignorePath(_directoryPathsToIgnore, path);
        }

        private boolean ignoreDirectory(Path path) {
            try {
                String rootRelativePath = GetRootRelativePathString(path);
                return ignoreDirectory(rootRelativePath);
            }
            catch (IllegalArgumentException e) {
                return false;
            }
        }

        private boolean ignoreFile(String path) {
            return ignorePath(_filePathsToIgnore, path);
        }

        private boolean ignoreFile(Path path) {
            try {
                String rootRelativePath = GetRootRelativePathString(path);
                return ignoreFile(rootRelativePath);
            }
            catch (IllegalArgumentException e) {
                return false;
            }
        }

        private HashMap<String, BasicFileAttributes> _directories = new HashMap<String, BasicFileAttributes>();
        private LinkedList<WalkFileTree.FileInfo> _files = new LinkedList<WalkFileTree.FileInfo>();

        private Path rootPath = null;
        private String rootPathString = null;

        public List<AsyncResponseRemerge> getWalkResult() {
            var files = new HashMap<String, List<LightRemoteInode>>();
            var lastModified = new HashMap<String, Long>();

            _directories.forEach((dir, attr) -> {
                files.put(dir.toString(), new LinkedList<LightRemoteInode>());
                lastModified.put(dir.toString(), attr.lastModifiedTime().toMillis());
            });
            for (var fi : _files) {
                var dirFiles = files.get(fi.rootRelativeParentPath);
                if (dirFiles == null) {
                    dirFiles = new LinkedList<LightRemoteInode>();
                    lastModified.put(fi.rootRelativeParentPath, (long)0);
                }

                var inode = new LightRemoteInode(
                    fi.path.getFileName().toString(),
                    "drftpd",
                    "drftpd",
                    fi.attr.isDirectory(),
                    fi.attr.lastModifiedTime().toMillis(),
                    fi.attr.size()
                );

                dirFiles.add(inode);
                files.put(fi.rootRelativeParentPath, dirFiles);
            }

            var result = new LinkedList<AsyncResponseRemerge>();
            files.forEach((dir, inodes) -> {
                var lm = lastModified.getOrDefault(dir, (long)0);

                inodes.sort(new Comparator<LightRemoteInode>() {
                    public int compare(LightRemoteInode o1, LightRemoteInode o2) {
                        return String.CASE_INSENSITIVE_ORDER.compare(o1.getName(), o2.getName());
                    }
                });

                var arr = new AsyncResponseRemerge(dir, inodes, lm);
                result.add(arr);
            });

            // master expects results depth first
            result.sort(new Comparator<AsyncResponseRemerge>() {
                public int compare(AsyncResponseRemerge o1, AsyncResponseRemerge o2) {
                    if (o1.getPath().equalsIgnoreCase(o2.getPath())) {
                        return 0;
                    }

                    long s1sepcount = o1.getPath().codePoints().filter(ch -> ch == '/').count();
                    long s2sepcount = o2.getPath().codePoints().filter(ch -> ch == '/').count();
                    
                    if (s1sepcount < s2sepcount) {
                        return 1;
                    }
                    else if (s1sepcount > s2sepcount) {
                        return -1;
                    }
                    else {
                        return o2.getPath().compareToIgnoreCase(o1.getPath());
                    }
                }
            });

            return result;
        }

        public void Walk(String path) throws IOException {
            rootPath = Paths.get(path).toRealPath();
            rootPathString = rootPath.toString();
            if (!rootPathString.endsWith(File.separator)) {
                rootPathString = rootPathString + File.separator;
            }

            Files.walkFileTree(rootPath, this);
        }

        public String GetRootRelativePathString(Path path) throws IllegalArgumentException {
            Path normalizedPath = path.normalize();
            if (!normalizedPath.startsWith(rootPath)) {
                throw new IllegalArgumentException(String.format("Path {} is not part of rootPath {}", path, rootPath));
            }
            return normalizedPath.toString().substring(rootPathString.length() - File.separator.length());
        }

        private void AddDir(Path dir, BasicFileAttributes attrs) {
            String rootRelativePath = "";
            try {
                rootRelativePath = GetRootRelativePathString(dir);
            }
            catch (IllegalArgumentException e) {
                return;
            }

            if (ignoreDirectory(rootRelativePath)) {
                return;
            }

            // Add parent first
            AddDir(dir.getParent(), null);

            if (attrs == null) {
                try {
                    attrs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                }
                catch (IOException e) {
                    logger.error("Could not read attributes for directory {}", dir, e);
                    return;
                }
            }

            if (attrs == null) {
                logger.error("Attributes for directory {} are missing", dir);
            }

            // keep newest modified time in case directory exists in multiple roots
            var value = _directories.get(rootRelativePath);
            if (value != null) {
                if ((attrs != null) && (attrs.lastModifiedTime().compareTo(value.lastModifiedTime()) > 0)) {
                    _directories.put(rootRelativePath, attrs);
                }
            }
            else {
                _directories.put(rootRelativePath, attrs);
            }
        }

        public class FileInfo {
            public final Path path;
            public final BasicFileAttributes attr;
            public final String rootRelativePath;
            public final String rootRelativeParentPath;

            public FileInfo(Path path, BasicFileAttributes attr, String rootRelativePath, String rootRelativeParentPath) {
                this.path = path;
                this.attr = attr;
                this.rootRelativePath = rootRelativePath;
                this.rootRelativeParentPath = rootRelativeParentPath;
            }
        }

        @Override
        public FileVisitResult preVisitDirectory(
            Path dir,
            BasicFileAttributes attrs
        )
        {
            try {
                if (!_slave.isOnline()) {
                    return FileVisitResult.TERMINATE;
                }

                String rootRelativePath = GetRootRelativePathString(dir);
                if (ignoreDirectory(rootRelativePath)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                AddDir(dir, attrs);

                if ((rootRelativePath != "") && (rootRelativePath != "/")) {
                    // Master expects subdirectories to appear in file list
                    String rootRelativeParentPath = GetRootRelativePathString(dir.getParent());
                    var fi = new FileInfo(dir, attrs, rootRelativePath, rootRelativeParentPath);
                    _files.add(fi);
                }

                return FileVisitResult.CONTINUE;
            }
            catch (IllegalArgumentException e) {
                logger.error("Error getting root relative path for {}", dir, e);
                return FileVisitResult.TERMINATE;
            }
        }

        @Override
        public FileVisitResult visitFile(
            Path file,
            BasicFileAttributes attrs)
        {
            try {
                String rootRelativePath = GetRootRelativePathString(file);
                String rootRelativeParentPath = GetRootRelativePathString(file.getParent());

                if (attrs.isRegularFile() && ignoreFile(rootRelativePath)) {
                    return FileVisitResult.CONTINUE;
                }
                else if (attrs.isDirectory() && ignoreDirectory(rootRelativePath)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if (attrs.isSymbolicLink()) {
                    // we ignore all symlinks
                }
                else if (attrs.isRegularFile()) {
                    AddDir(file.getParent(), null);

                    var fi = new FileInfo(file, attrs, rootRelativePath, rootRelativeParentPath);
                    _files.add(fi);
                }
                else if (attrs.isDirectory()) {
                    // directory should have been added in preVisitDirectory, adding in case preVisitDirectory missed attributes
                    AddDir(file, attrs);
                }

                return FileVisitResult.CONTINUE;
            }
            catch (IllegalArgumentException e) {
                logger.error("Error getting root relative path for {}", file, e);
                return FileVisitResult.TERMINATE;
            }
        }

        @Override
        public FileVisitResult postVisitDirectory(
            Path dir,
            IOException exc
        )
        {
            try {
                String path = GetRootRelativePathString(dir);

                if (exc != null) {
                    if (!ignoreDirectory(path)) {
                        logger.error("Failed to visit directory: " + dir.toString(), exc);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            catch (IllegalArgumentException e) {
                logger.error("Error getting root relative path for {}", dir, e);
                return FileVisitResult.TERMINATE;
            }
        }

        @Override
        public FileVisitResult visitFileFailed(
            Path path,
            IOException exc
        )
        {
            if (!ignoreDirectory(path) && !ignoreFile(path)) {
                logger.error("Failed to visit path: " + path.toString(), exc);
            }
            return FileVisitResult.CONTINUE;
        }
    }

}
