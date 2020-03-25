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
package org.drftpd.commands.sections;

import org.drftpd.master.GlobalContext;
import org.drftpd.master.sections.SectionInterface;
import org.drftpd.plugins.commandmanager.CommandInterface;
import org.drftpd.plugins.commandmanager.CommandRequest;
import org.drftpd.plugins.commandmanager.CommandResponse;
import org.drftpd.plugins.commandmanager.StandardCommandManager;

import java.util.*;

/**
 * @author mog
 * @version $Id$
 */
public class Sections extends CommandInterface {
	private ResourceBundle _bundle;
	
	public void initialize(String method, String pluginName, StandardCommandManager cManager) {
    	super.initialize(method, pluginName, cManager);
    	_bundle = cManager.getResourceBundle();

    }

    public CommandResponse doSITE_SECTIONS(CommandRequest request) {
        CommandResponse response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");

        Map<String, Object> env = new HashMap<>();

        ArrayList<SectionInterface> sections =
                new ArrayList<>(GlobalContext.getGlobalContext().getSectionManager().getSections());
        
       sections.sort(new SectionComparator());
        
        for (SectionInterface section : sections) {
            env.put("section", section.getName());
			env.put("sectioncolor", section.getName());
            env.put("path", section.getCurrentDirectory().getPath());
            response.addComment(request.getSession().jprintf(_bundle, "section", env, request.getUser()));
        }

        return response;
    }
    
    private static class SectionComparator implements Comparator<SectionInterface> {
		public int compare(SectionInterface o1, SectionInterface o2) {
			return o1.getName().compareToIgnoreCase(o2.getName());
		}    	
    }
}

