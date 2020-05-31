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
package org.drftpd.trial.master;

import org.drftpd.master.commands.CommandInterface;
import org.drftpd.master.commands.CommandRequest;
import org.drftpd.master.commands.CommandResponse;
import org.drftpd.master.commands.StandardCommandManager;

import java.util.ResourceBundle;

/**
 * @author CyBeR
 * @version $Id: TrialManagerCommands.java 1925 2009-06-15 21:46:05Z CyBeR $
 */

public class TrialManagerCommands extends CommandInterface {
    private ResourceBundle _bundle;

    public void initialize(String method, String pluginName, StandardCommandManager cManager) {
        super.initialize(method, pluginName, cManager);
        _bundle = cManager.getResourceBundle();
    }

    public CommandResponse doTOP(CommandRequest request) {
        CommandResponse response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
        boolean didRun = false;
        TrialManager trialmanager = TrialManager.getTrialManager();
        for (TrialType trialtype : trialmanager.getTrials()) {
            trialtype.doTop(request, _bundle, response);
            didRun = true;
        }

        if (!didRun) {
            response.addComment("No Trial Types Loaded With This Command");
        }
        return response;
    }

    public CommandResponse doCUT(CommandRequest request) {
        CommandResponse response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
        boolean didRun = false;
        TrialManager trialmanager = TrialManager.getTrialManager();
        for (TrialType trialtype : trialmanager.getTrials()) {
            trialtype.doCut(request, _bundle, response);
            didRun = true;
        }

        if (!didRun) {
            response.addComment("No Trial Types Loaded With This Command");
        }
        return response;
    }

    public CommandResponse doPASSED(CommandRequest request) {
        CommandResponse response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");
        boolean didRun = false;
        TrialManager trialmanager = TrialManager.getTrialManager();
        for (TrialType trialtype : trialmanager.getTrials()) {
            trialtype.doPassed(request, _bundle, response);
            didRun = true;
        }

        if (!didRun) {
            response.addComment("No Trial Types Loaded With This Command");
        }
        return response;
    }

    public CommandResponse doTEST(CommandRequest request) {
        CommandResponse response = StandardCommandManager.genericResponse("RESPONSE_200_COMMAND_OK");

        TrialManager trialmanager = TrialManager.getTrialManager();
        for (TrialType trialtype : trialmanager.getTrials()) {
            if (trialtype.getPeriod() == 1) {
                trialtype.doTrial();
            }
        }
        return response;
    }

}
