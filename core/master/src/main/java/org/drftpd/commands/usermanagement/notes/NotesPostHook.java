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
package org.drftpd.commands.usermanagement.notes;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.drftpd.commands.usermanagement.notes.metadata.NotesData;
import org.drftpd.master.GlobalContext;
import org.drftpd.master.common.dynamicdata.KeyNotFoundException;
import org.drftpd.master.usermanager.NoSuchUserException;
import org.drftpd.master.usermanager.User;
import org.drftpd.master.usermanager.UserFileException;
import org.drftpd.commands.CommandRequest;
import org.drftpd.commands.CommandResponse;
import org.drftpd.commands.PostHookInterface;
import org.drftpd.commands.StandardCommandManager;

import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * @author Scitz0
 */
public class NotesPostHook implements PostHookInterface {
	private static final Logger logger = LogManager.getLogger(NotesPostHook.class);
	private ResourceBundle _bundle;


	public void initialize(StandardCommandManager manager) {
		_bundle = manager.getResourceBundle();

	}

	public void doNotesPostHook(CommandRequest request, CommandResponse response) {
		User myUser;
		try {
			myUser = GlobalContext.getGlobalContext().getUserManager()
					.getUserByNameUnchecked(request.getArgument());
		} catch (NoSuchUserException | UserFileException ex) {
			return;
		}
		try {
			// Test if metadata exist for user and if so add to response
			NotesData notes = myUser.getKeyedMap().getObject(NotesData.NOTES);
			Map<String, Object> env = new HashMap<>();
			int cnt = 1;
			for (String note : notes.getNotes()) {
				env.put("number", cnt++);
				env.put("note", note);
				response.addComment(request.getSession().jprintf(_bundle, "note", env, myUser));
			}

		} catch (KeyNotFoundException e) {
			// ignore
		}
	}
}