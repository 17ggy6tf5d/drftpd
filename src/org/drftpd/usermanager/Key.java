/**
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
package org.drftpd.usermanager;


/**
 * @author mog
 * @version $Id: Key.java,v 1.1 2004/11/05 13:27:23 mog Exp $
 */
public class Key {
    private String _key;
    private Class _owner;
    private Class _type;

    public Key(Class owner, String key, Class type) {
        _owner = owner;
        _key = key;
        _type = type;
    }

    public String getKey() {
        return _key;
    }

    public Class getOwner() {
        return _owner;
    }

    public Class getType() {
        return _type;
    }

    public String toString() {
        return getOwner().getName() + '@' + getKey();
    }
}