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

package com.persistit.logging;

import org.apache.commons.logging.Log;

/**
 * Wraps an <code>org.apache.commons.logging.Log</code> for Persistit logging.
 * Code to enable default logging through Apache commons logging is shown here:
 * <code><pre>
 *    // refer to any appropriate org.apache.commons.logging.Log, for example
 *    Log log = LogFactory.getLog(getClass());
 *    Persistit.setPersistitLogger(new ApacheCommonsAdapter(logger));
 * </pre></code>
 * 
 * @version 1.1
 */
public class ApacheCommonsLogAdapter implements PersistitLogger {
    /**
     * The Log object wrapped by this adapter.
     */
    private Log _logger;

    /**
     * Wraps an Apache commons <code>Log</code> so that Persistit can write to
     * it.
     * 
     * @param log
     *            A <code>Log</code> to which Persistit log messages will be
     *            directed.
     */
    public ApacheCommonsLogAdapter(Log log) {
        _logger = log;
    }

    /**
     * Overrides <code>isLoggable</code> to allow control by the wrapped
     * <code>Logger</code>.
     * 
     * @param level
     *            The <code>level</code>
     */
    @Override
    public boolean isLoggable(PersistitLevel level) {
        switch (level) {
        case NONE:
            return false;
        case TRACE:
            return _logger.isTraceEnabled();
        case DEBUG:
            return _logger.isDebugEnabled();
        case INFO:
            return _logger.isInfoEnabled();
        case WARNING:
            return _logger.isWarnEnabled();
        case ERROR:
            return _logger.isErrorEnabled();
        default:
            throw new RuntimeException("base switch");
        }
    }

    /**
     * Writes a log message generated by Persistit to the wrapped
     * <code>Logger</code>.
     * 
     * @param level
     *            The <code>PersistitLevel</code>
     * @param message
     *            The message to write to the log.
     */
    @Override
    public void log(PersistitLevel level, String message) {
        switch (level) {
        case NONE:
            break;
        case TRACE:
            _logger.trace(message);
            break;
        case DEBUG:
            _logger.debug(message);
            break;
        case INFO:
            _logger.info(message);
            break;
        case WARNING:
            _logger.warn(message);
            break;
        case ERROR:
            _logger.error(message);
            break;
        default:
            throw new RuntimeException("base switch");
        }
    }

    @Override
    public void open() {
        // Nothing to do - the log is created and destroyed by the embedding application
    }

    @Override
    public void close() {
        // Nothing to do - the log is created and destroyed by the embedding application
    }

}
