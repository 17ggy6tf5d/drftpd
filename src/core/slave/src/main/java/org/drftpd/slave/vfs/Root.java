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

import org.drftpd.common.io.PhysicalFile;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.io.File;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * @author mog
 * @version $Id$
 */
public class Root {
    private static final String separator = "/";
    private final PhysicalFile _rootFile;
    private long _lastModified;

    public Root(String root) throws IOException {
        _rootFile = new PhysicalFile(new PhysicalFile(root).getCanonicalFile());
        _lastModified = getFile().lastModified();
    }

    public PhysicalFile getFile() {
        return _rootFile;
    }

    public String getPath() {
        return _rootFile.getPath();
    }

    public long lastModified() {
        return _lastModified;
    }

    public void touch() {
        getFile().setLastModified(_lastModified = System.currentTimeMillis());
    }

    public String toString() {
        return "[root=" + getPath() + "]";
    }

    public long getDiskSpaceAvailable() {
        return getFile().getUsableSpace();
    }

    public long getDiskSpaceCapacity() {
        return getFile().getTotalSpace();
    }

    public PhysicalFile getFile(String path) {
        return new PhysicalFile(getPath() + separator + path);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    //@Override
    public boolean equals(Object arg0) {
        if (!(arg0 instanceof Root)) {
            return false;
        }
        Root r = (Root) arg0;
        return r.getPath().equals(getPath());
    }

    public void getAllInodes(HashMap<String, List<LightRemoteInode>> inodes, HashMap<String, Long> lastModified, BooleanSupplier cancelled)
        throws IllegalArgumentException, IOException
    {
        if (inodes == null {
            throw new IllegalArgumentException();
        }
        var walker = new FileTreeWalker(inodes, lastmodified, cancelled);
        walker.Walk(getPath());
    }

    private class FileTreeInfo {
        public final Path path;
        public final BasicFileAttributes attr;

        // store String representation for faster lookup
        public final String rootRelativePath;
        public final String rootRelativeParentPath;

        public FileInfo(Path path, BasicFileAttributes attr, String rootRelativePath, String rootRelativeParentPath) {
            this.path = path;
            this.attr = attr;
            this.rootRelativePath = rootRelativePath;
            this.rootRelativeParentPath = rootRelativeParentPath;
        }
    }

    public class FileTreeWalker extends SimpleFileVisitor<Path>
    {
        private final HashMap<String, List<LightRemoveInode>> _inodes;
        private final HashMap<String, Long> _lastModified;
        private final BooleanSupplier _cancelled;

        publid FileTreeWalker(HashMap<String, List<LightRemoveInode>> inodes, HashMap<String, Long> lastModified, BooleanSupplier cancelled) {
            _inodes = inodes;
            _lastModified = lastModified;
            _cancelled = cancelled;
        }

        private HashMap<String, BasicFileAttributes> _directories = new HashMap<String, BasicFileAttributes>();
        private LinkedList<WalkFileTree.FileInfo> _files = new LinkedList<WalkFileTree.FileInfo>();

        private Path rootPath = null;
        private String rootPathString = null;

        public HashMap<String, List<LightRemoteInode>> Walk(String path) throws IOException {
            rootPath = Paths.get(path).toRealPath();
            rootPathString = rootPath.toString();
            if (!rootPathString.endsWith(File.separator)) {
                rootPathString = rootPathString + File.separator;
            }

            Files.walkFileTree(rootPath, this);

            var files = new HashMap<String, List<LightRemoteInode>>();

            _directories.forEach((dir, attr) -> {
                files.put(dir.toString(), new LinkedList<LightRemoteInode>());
                _lastModified.put(dir.toString(), attr.lastModifiedTime().toMillis());
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

            return files;
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

            // keep newest modified time in case directory exists in multiple roots
            var value = _directories.get(rootRelativePath);
            if ((value == null) && (attrs == null)) {
                logger.error("Attributes for directory {} are missing", dir);
                return;
            }

            if ( (value == null) || ((attrs != null) && (attrs.lastModifiedTime().compareTo(value.lastModifiedTime()) > 0)) ) {
                _directories.put(rootRelativePath, attrs);
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

                if (attrs.isSymbolicLink()) {
                    logger.warn("You have a symbolic link {} -- these are ignored by drftpd", file);
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
            logger.error("Failed to visit directory: " + dir.toString(), exc);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(
            Path path,
            IOException exc
        )
        {
            logger.error("Failed to visit path: " + path.toString(), exc);
            return FileVisitResult.CONTINUE;
        }
    }

}
