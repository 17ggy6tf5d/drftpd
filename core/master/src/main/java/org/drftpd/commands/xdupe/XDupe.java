package org.drftpd.commands.xdupe;

import org.drftpd.master.common.dynamicdata.Key;
import org.drftpd.commands.CommandInterface;
import org.drftpd.commands.CommandRequest;
import org.drftpd.commands.CommandResponse;
import org.drftpd.commands.StandardCommandManager;

public class XDupe extends CommandInterface {
	
	public static final Key<Integer> XDUPE = new Key<>(XDupe.class, "XDUPE");

	public void initialize(String method, String pluginName, StandardCommandManager cManager) {
		super.initialize(method, pluginName, cManager);
		_featReplies = new String[] {
				"SITE XDUPE"
		};
	}
	
	public CommandResponse doSITE_XDUPE(CommandRequest request) {
		int xDupe = request.getSession().getObjectInteger(XDUPE);
			
		if (!request.hasArgument()) {
			if (xDupe == 0) {
				return new CommandResponse(200, "Extended dupe mode is disabled.");
			}
			return new CommandResponse(200, "Extended dupe mode " + xDupe + " is enabled.");
		}
		int myXdupe;
		
		try {
			myXdupe = Integer.parseInt(request.getArgument());
		} catch (NumberFormatException ex) {
			return StandardCommandManager.genericResponse("RESPONSE_501_SYNTAX_ERROR");
		}

		if (myXdupe < 0 || myXdupe > 4) {
			return StandardCommandManager.genericResponse("RESPONSE_504_COMMAND_NOT_IMPLEMENTED_FOR_PARM");
		}

		request.getSession().setObject(XDUPE, myXdupe);
		return new CommandResponse(200, "Activated extended dupe mode " + myXdupe + ".");
	}
}
