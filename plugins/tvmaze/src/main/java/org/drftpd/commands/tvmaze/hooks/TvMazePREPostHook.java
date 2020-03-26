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
package org.drftpd.commands.tvmaze.hooks;
import org.drftpd.commands.pre.Pre;
import org.drftpd.commands.tvmaze.TvMazeConfig;
import org.drftpd.commands.tvmaze.TvMazePrintThread;
import org.drftpd.commands.tvmaze.TvMazeUtils;
import org.drftpd.common.CommandHook;
import org.drftpd.common.HookType;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.sections.SectionInterface;
import org.drftpd.master.vfs.DirectoryHandle;
import org.drftpd.commands.CommandRequest;
import org.drftpd.commands.CommandResponse;

/**
 * @author scitz0
 */

public class TvMazePREPostHook {

	@CommandHook(commands = "doSITE_PRE", priority = 100, type = HookType.PRE)
	public void doPostHook(CommandRequest request, CommandResponse response) {
		if (response.getCode() != 250) {
			// PRE Failed, skip
			return;
		}

		// PRE dir
		DirectoryHandle preDir = response.getObject(Pre.PREDIR, null);
		if (preDir == null) {
			// no pre dir found, how can this be on successful PRE?
			return;
		}

		SectionInterface sec = GlobalContext.getGlobalContext().getSectionManager().lookup(preDir);
		if (!TvMazeUtils.containSection(sec, TvMazeConfig.getInstance().getRaceSections()))  {
			return;
		}

		if (preDir.getName().matches(TvMazeConfig.getInstance().getExclude())) {
			return;
		}

		// TvMaze data collected in TvMazeConfig by listening on the VirtualFileSystemInodeCreatedEvent event
		// Spawn a print thread that waits on the result to print the TvMaze info
		TvMazePrintThread tvmazePrintThread = new TvMazePrintThread(preDir, sec);
		tvmazePrintThread.start();
	}
}