/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.drftpd.vfs;

import java.io.FileNotFoundException;

import org.drftpd.master.RemoteSlave;

/**
 * @author zubov
 * @version $Id$
 */
public class LinkHandle extends InodeHandle implements LinkHandleInterface {

	public LinkHandle(String path) {
		super(path);
	}

	@Override
	protected VirtualFileSystemLink getInode() throws FileNotFoundException {
		VirtualFileSystemInode inode = super.getInode();
		if (inode instanceof VirtualFileSystemLink) {
			return (VirtualFileSystemLink) inode;
		}
		throw new ClassCastException("LinkHandle object pointing to Inode:"
				+ inode);
	}

	public DirectoryHandle getTargetDirectory() throws FileNotFoundException,
			ObjectNotValidException {
		return getParent().getDirectory(getInode().getLink());
	}
	
	public String getTargetString() throws FileNotFoundException {
		return getInode().getLink();
	}

	@Override
	public void removeSlave(RemoteSlave rslave) throws FileNotFoundException {
		// we don't have anything to remove
	}

	@Override
	public boolean isDirectory() {
		return false;
	}

	@Override
	public boolean isFile() {
		return false;
	}

	@Override
	public boolean isLink() {
		return true;
	}

}
