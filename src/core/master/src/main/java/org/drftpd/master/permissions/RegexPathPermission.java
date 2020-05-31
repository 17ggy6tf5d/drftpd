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
package org.drftpd.master.permissions;

import org.drftpd.master.vfs.InodeHandle;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegexPathPermission extends PathPermission {
    private final Pattern _pat;

    public RegexPathPermission(Pattern pat, Collection<String> users) {
        super(users);
        _pat = pat;
    }

    public boolean checkPath(InodeHandle inode) {
        Matcher m = _pat.matcher(inode.getPath());
        return m.find();
    }

    public String toString() {
        return getClass().getCanonicalName() + "[pat=" + _pat.toString() + ",users=" + _users.toString() + "]";
    }
}
