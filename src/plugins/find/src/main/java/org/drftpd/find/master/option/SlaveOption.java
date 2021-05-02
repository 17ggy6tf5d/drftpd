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
package org.drftpd.find.master.option;

import org.drftpd.find.master.FindSettings;
import org.drftpd.find.master.FindUtils;
import org.drftpd.master.commands.ImproperUsageException;
import org.drftpd.master.indexation.AdvancedSearchParams;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

/**
 * @author scitz0
 * @version $Id$
 */
public class SlaveOption implements OptionInterface {

    private final Map<String, String> _options = Map.of(
            "slaves", "<slave[ slave ..]> # Slave(s) to include in the search",
            "nbrofslaves", "<min>:<max> # The found entries need to have at least min slaves holding the files and max slaves"
    );

    @Override
    public Map<String, String> getOptions() {
        return _options;
    }

    @Override
    public void executeOption(String option, String[] args, AdvancedSearchParams params, FindSettings settings) throws ImproperUsageException {
        if (args == null) {
            throw new ImproperUsageException("Missing argument for " + option + " option");
        }
        if (option.equalsIgnoreCase("-slaves")) {
            HashSet<String> slaves = new HashSet<>(Arrays.asList(args));
            params.setSlaves(slaves);
        } else if (option.equalsIgnoreCase("-nbrofslaves")) {
            try {
                String[] range = FindUtils.getRange(args[0], ":");
                if (range[0] != null && range[1] != null) {
                    int minNbrOfSlaves = Integer.parseInt(range[0]);
                    int maxNbrOfSlaves = Integer.parseInt(range[1]);
                    if (minNbrOfSlaves > maxNbrOfSlaves) {
                        throw new ImproperUsageException("Slave number range invalid, min value higher than max");
                    }
                    params.setMinSlaves(minNbrOfSlaves);
                    params.setMaxSlaves(maxNbrOfSlaves);
                } else if (range[0] != null) {
                    params.setMinSlaves(Integer.parseInt(range[0]));
                } else if (range[1] != null) {
                    params.setMaxSlaves(Integer.parseInt(range[1]));
                }
            } catch (NumberFormatException e) {
                throw new ImproperUsageException(e);
            }
        }
    }
}
