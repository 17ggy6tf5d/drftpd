/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 *
 * DrFTPD is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * DrFTPD; if not, write to the Free Software Foundation, Inc., 59 Temple Place,
 * Suite 330, Boston, MA 02111-1307 USA
 */
package org.drftpd.master.commands.imdb.event;

import org.drftpd.master.vfs.DirectoryHandle;
import org.tanesha.replacer.ReplacerEnvironment;

/**
 * @author lh
 */
public class IMDBEvent {

	private ReplacerEnvironment _env;
	private DirectoryHandle _dir;
	
	public IMDBEvent(ReplacerEnvironment env, DirectoryHandle dir) {
		_env = env;
		_dir = dir;
	}

	public ReplacerEnvironment getEnv() {
		return _env;
	}

	public DirectoryHandle getDir() {
		return _dir;
	}
}