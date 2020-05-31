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
package org.drftpd.master.vfs.event;

import org.drftpd.master.vfs.VirtualFileSystemInode;

/**
 * This event is fired whenever a rename happens in the Virtual File System.
 *
 * @author flavio
 * @version $Id$
 */
public class VirtualFileSystemRenameEvent extends VirtualFileSystemEvent {

    private final ImmutableInodeHandle _source;

    public VirtualFileSystemRenameEvent(String sourcePath, VirtualFileSystemInode destination, String destinationPath) {
        super(destination, destinationPath);

        _source = new ImmutableInodeHandle(destination, sourcePath);
    }

    /**
     * @return where the file is being renamed to.
     */
    public ImmutableInodeHandle getSource() {
        return _source;
    }

}
