/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.persistit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Selects Volumes, Trees or Keys given a pattern string. The CLI utilities use
 * this to select Volumes and/or Trees. Syntax:
 * 
 * <pre>
 * <i>volpattern</i>[/<i>treepattern</i>[<i>keyfilter</i>],...
 * </pre>
 * 
 * where <i>volpattern</i> and <i>treepattern</i> are pattern strings that use
 * "*" and "?" as multi-character and single-character wild-cards.
 * (Alternatively, if the <code>regex</code> flag is set, these are true regular
 * expressions.) Example:
 * 
 * <code><pre>
 * v1/*index*{"a"-"f"},*data/*
 * </pre></code>
 * 
 * selects all trees in volume named "v1" having names containing the substring
 * "index", and all tress in all values having names that end with "data". For
 * trees selected in volume v1, there is a keyfilter that specifies keys
 * starting with letters 'a' through 'f'.
 * <p />
 * The {@link #parseSelector(String, boolean, char)} method takes a quote
 * character, normally '\\', that may be used to quote the meta characters in
 * patterns, commas and forward slashes.
 * 
 * @author peter
 */
public class TreeSelector {

    private final static char WILD_MULTI = '*';
    private final static char WILD_ONE = '?';
    private final static char COMMA = ',';
    private final static char COLON = ':';
    private final static char LBRACE = '{';
    private final static char RBRACE = '}';
    private final static char NUL = (char) 0;
    private final static String CAN_BE_QUOTED = "*?,";

    /**
     * Constraints on volume name, tree name and/or key.
     */
    private static class Selector {
        Pattern _vpattern;
        Pattern _tpattern;
        KeyFilter _keyFilter;

        private boolean isNull() {
            return _vpattern == null && _tpattern == null && _keyFilter == null;
        }

    }

    private static enum State {
        V, T, K, C
    }

    public static TreeSelector parseSelector(final String spec, final boolean regex, final char quote) {
        TreeSelector treeSelector = new TreeSelector();
        Selector s = new Selector();
        State state = State.V;
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        int size = spec.length() + 1;
        for (int index = 0; index < size; index++) {
            char c = index + 1 < size ? spec.charAt(index) : (char) 0;
            if (quoted) {
                sb.append(c);
                quoted = false;
            } else if (c == quote && index < size - 2 && CAN_BE_QUOTED.indexOf(spec.charAt(index + 1)) > 0) {
                quoted = true;
            } else {
                switch (state) {
                case V:
                    if (c == NUL && sb.length() == 0) {
                        break;
                    }
                    if (c == WILD_MULTI && !regex) {
                        sb.append(".*");
                    } else if (c == WILD_ONE && !regex) {
                        sb.append(".");
                    } else if (c == COLON || c == COMMA || c == NUL) {
                        s._vpattern = Pattern.compile(sb.toString());
                        sb.setLength(0);
                        if (c == COLON) {
                            state = State.T;
                        } else {
                            treeSelector._terms.add(s);
                            s = new Selector();
                        }
                    } else {
                        sb.append(c);
                    }
                    break;
                case T:
                    if (c == WILD_MULTI && !regex) {
                        sb.append(".*");
                    } else if (c == WILD_ONE && !regex) {
                        sb.append(".");
                    } else if (c == LBRACE || c == COMMA || c == NUL) {
                        s._tpattern = Pattern.compile(sb.toString());
                        sb.setLength(0);
                        if (c == LBRACE) {
                            state = State.K;
                            sb.append(c);
                        } else {
                            treeSelector._terms.add(s);
                            s = new Selector();
                        }
                    } else {
                        sb.append(c);
                    }
                    break;
                case K:
                    sb.append(c);
                    if (c == RBRACE || c == NUL) {
                        s._keyFilter = new KeyParser(sb.toString()).parseKeyFilter();
                        treeSelector._terms.add(s);
                        s = new Selector();
                        sb.setLength(0);
                        state = State.C;
                    }
                    break;
                case C:
                    if (c == COMMA || c == NUL) {
                        state = State.V;
                    } else {
                        throw new IllegalArgumentException("at index=" + index);
                    }
                }
            }
        }
        return treeSelector;
    }

    private final List<Selector> _terms = new ArrayList<Selector>();

    public boolean isEmpty() {
        return _terms.isEmpty();
    }

    public int size() {
        return _terms.size();
    }

    public boolean isSelected(final Volume volume) {
        return isVolumeNameSelected(volume.getName());
    }

    public boolean isSelected(final Tree tree) {
        return isTreeNameSelected(tree.getVolume().getName(), tree.getName());
    }

    public boolean isVolumeNameSelected(final String volumeName) {
        if (_terms.isEmpty()) {
            return true;
        }
        for (final Selector selector : _terms) {
            if (selector._vpattern == null || selector._vpattern.matcher(volumeName).matches()) {
                return true;
            }
        }
        return false;
    }

    public boolean isVolumeOnlySelection(final String volumeName) {
        if (_terms.isEmpty()) {
            return true;
        }
        for (final Selector selector : _terms) {
            if ((selector._vpattern == null || selector._vpattern.matcher(volumeName).matches())
                    && selector._tpattern == null && selector._keyFilter == null) {
                return true;
            }
        }
        return false;
    }

    public boolean isTreeNameSelected(final String volumeName, final String treeName) {
        if (_terms.isEmpty()) {
            return true;
        }
        for (final Selector selector : _terms) {
            if ((selector._vpattern == null || selector._vpattern.matcher(volumeName).matches())
                    && (selector._tpattern == null || selector._tpattern.matcher(treeName).matches())) {
                return true;
            }
        }
        return false;
    }

    public KeyFilter keyFilter(final String volumeName, final String treeName) {
        KeyFilter kf = null;
        for (final Selector selector : _terms) {
            if ((selector._vpattern == null || selector._vpattern.matcher(volumeName).matches())
                    && (selector._tpattern == null || selector._tpattern.matcher(treeName).matches())) {
                if (kf == null) {
                    kf = selector._keyFilter;
                    if (kf == null) {
                        kf = new KeyFilter();
                    }
                } else {
                    throw new IllegalStateException("Non-unique KeyFilters for tree " + volumeName + "/" + treeName);
                }
            }
        }
        return kf;
    }

}
