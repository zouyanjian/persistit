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

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import com.persistit.CLI.Arg;
import com.persistit.CLI.Cmd;
import com.persistit.exception.CorruptImportStreamException;
import com.persistit.exception.PersistitException;

/**
 * Loads Persistit records from a file or other stream in a format generated by
 * a {@link StreamSaver}.
 * 
 * @version 1.0
 */
public class StreamLoader extends Task {

    /**
     * Default for BufferedInputStream buffer size.
     */
    public final static int DEFAULT_BUFFER_SIZE = 65536;
    protected String _filePath;
    protected DataInputStream _dis;

    protected Key _key = new Key((Persistit) null);
    protected Value _value = new Value((Persistit) null);
    protected Volume _lastVolume;
    protected Tree _lastTree;
    protected int _dataRecordCount = 0;
    protected int _otherRecordCount = 0;
    protected boolean _stop;
    protected Exception _lastException;
    protected boolean _verbose;

    protected TreeSelector _treeSelector;
    protected boolean _createMissingVolumes;
    protected boolean _createMissingTrees;
    protected ImportHandler _handler;

    @Cmd("load")
    static Task createStreamLoader(@Arg("file|string:|Load from file path") String file,
            @Arg("trees|string:|Tree selector - specify Volumes/Trees/Keys to save") String treeSelectorString,
            @Arg("_flag|r|Use regular expressions in tree selector") boolean regex,
            @Arg("_flag|n|Don't create missing Volumes (Default is to create them)") boolean dontCreateVolumes,
            @Arg("_flag|t|Don't create missing Trees (Default is to create them)") boolean dontCreateTrees)
            throws Exception {

        StreamLoader task = new StreamLoader();
        task._filePath = file;
        task._treeSelector = TreeSelector.parseSelector(treeSelectorString, regex, '\\');
        task._createMissingVolumes = !dontCreateVolumes;
        task._createMissingTrees = !dontCreateTrees;
        return task;
    }

    /**
     * Package-private constructor for use in a {@link Task}.
     * 
     */
    StreamLoader() {
    }

    public StreamLoader(final Persistit persistit, final DataInputStream dis) {
        super(persistit);
        _dis = dis;
    }

    public StreamLoader(final Persistit persistit, final File file) throws IOException {
        this(persistit, new DataInputStream(new BufferedInputStream(new FileInputStream(file))));
    }

    public StreamLoader(final Persistit persistit, final String fileName) throws IOException {
        this(persistit, new DataInputStream(new BufferedInputStream(new FileInputStream(fileName))));
    }

    public boolean isVerbose() {
        return _verbose;
    }

    public void setVerbose(boolean b) {
        _verbose = b;
    }

    public void close() throws IOException {
        _dis.close();
    }

    public void load() throws IOException, PersistitException {
        load(new TreeSelector(), true, true);
    }

    public void load(TreeSelector treeSelector, boolean createMissingVolumes, boolean createMissingTrees)
            throws IOException, PersistitException {
        _handler = new ImportHandler(_persistit, treeSelector, createMissingVolumes, createMissingTrees);
        load(_handler);
        close();
    }

    public void load(ImportHandler handler) throws IOException, PersistitException {
        while (readAndDispatchOneRecord(handler)) {
            poll();
        }
    }

    private boolean readAndDispatchOneRecord(ImportHandler handler) throws PersistitException, IOException {
        int b1 = _dis.read();
        if (b1 == -1)
            return false;

        int b2 = _dis.read();
        int recordType = ((b1 & 0xFF) << 8) + (b2 & 0xFF);

        switch (recordType) {
        case StreamSaver.RECORD_TYPE_FILL: {
            handler.handleFillRecord();
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_DATA: {
            int keySize = _dis.readShort();
            int elisionCount = _dis.readShort();
            int valueSize = _dis.readInt();
            _value.ensureFit(valueSize);
            _dis.read(_key.getEncodedBytes(), elisionCount, keySize - elisionCount);
            _key.setEncodedSize(keySize);
            _dis.read(_value.getEncodedBytes(), 0, valueSize);
            _value.setEncodedSize(valueSize);
            handler.handleDataRecord(_key, _value);
            _dataRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_KEY_FILTER: {
            String filterString = _dis.readUTF();
            handler.handleKeyFilterRecord(filterString);
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_VOLUME_ID: {
            long id = _dis.readLong();
            long initialPages = _dis.readLong();
            long extensionPages = _dis.readLong();
            long maximumPages = _dis.readLong();
            int bufferSize = _dis.readInt();
            String path = _dis.readUTF();
            String name = _dis.readUTF();
            handler.handleVolumeIdRecord(id, initialPages, extensionPages, maximumPages, bufferSize, path, name);
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_TREE_ID: {
            String treeName = _dis.readUTF();
            handler.handleTreeIdRecord(treeName);
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_HOSTNAME: {
            String hostName = _dis.readUTF();
            handler.handleHostNameRecord(hostName);
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_USER: {
            String hostName = _dis.readUTF();
            handler.handleUserRecord(hostName);
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_COMMENT: {
            String comment = _dis.readUTF();
            handler.handleCommentRecord(comment);
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_COUNT: {
            long dataRecordCount = _dis.readLong();
            long otherRecordCount = _dis.readLong();
            handler.handleCountRecord(dataRecordCount, otherRecordCount);
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_START: {
            handler.handleStartRecord();
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_END: {
            handler.handleEndRecord();
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_TIMESTAMP: {
            long timeStamp = _dis.readLong();
            handler.handleTimeStampRecord(timeStamp);
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_EXCEPTION: {
            String exceptionString = _dis.readUTF();
            handler.handleExceptionRecord(exceptionString);
            _otherRecordCount++;
            break;
        }
        case StreamSaver.RECORD_TYPE_COMPLETION: {
            handler.handleCompletionRecord();
            _otherRecordCount++;
            return false;
        }
        default: {
            throw new CorruptImportStreamException("Invalid record type " + recordType + " ("
                    + Util.bytesToHex(new byte[] { (byte) (recordType >>> 8), (byte) recordType }) + " after reading "
                    + _dataRecordCount + " data records" + " and " + _otherRecordCount + " other records");
        }
        }
        if ((_dataRecordCount + _otherRecordCount) % 100 == 0)
            poll();
        return true;
    }

    public static class ImportHandler {
        protected Persistit _persistit;
        protected TreeSelector _treeSelector;
        protected Exchange _exchange;
        protected Volume _volume;
        protected Tree _tree;
        protected KeyFilter _keyFilter;
        protected boolean _createMissingVolumes;
        protected boolean _createMissingTrees;

        public ImportHandler(Persistit persistit) {
            this(persistit, new TreeSelector(), true, true);
        }

        public ImportHandler(Persistit persistit, TreeSelector treeSelector, boolean createMissingVolumes,
                boolean createMissingTrees) {
            _persistit = persistit;
            _treeSelector = treeSelector == null ? new TreeSelector() : treeSelector;
            _createMissingTrees = createMissingTrees;
            _createMissingVolumes = createMissingVolumes;
        }

        public void handleFillRecord() throws PersistitException {
        }

        public void handleDataRecord(Key key, Value value) throws PersistitException {
            if (_keyFilter == null || _keyFilter.selected(key)) {
                if (_volume == null || _tree == null)
                    return;
                if (_exchange == null) {
                    _exchange = _persistit.getExchange(_volume, _tree.getName(), false);
                }
                key.copyTo(_exchange.getKey());
                _exchange.setSplitPolicy(SplitPolicy.PACK_BIAS);
                // Using this package-private method avoids copying
                // the value field.
                _exchange.store(_exchange.getKey(), value);
            }
        }

        public void handleKeyFilterRecord(String keyFilterString) throws PersistitException {
        }

        public void handleVolumeIdRecord(long volumeId, long initialPages, long extensionPages, long maximumPages,
                int bufferSize, String path, String name) throws PersistitException {
            Exchange oldExchange = _exchange;
            _exchange = null;
            _volume = null;
            _tree = null;

            if (!_treeSelector.isVolumeNameSelected(name)) {
                return;
            }

            final Volume v1 = _persistit.getVolume(volumeId);
            final Volume v2 = _persistit.getVolume(name);

            if (v1 == null) {
                _volume = v2;
            } else if (v2 == null) {
                _volume = v1;
            } else if (v1 == v2) {
                _volume = v1;
            } else {
                // handle mismatched volume name/id
                return;
            }

            if (_volume == null && _createMissingVolumes) {
                _volume = Volume.create(_persistit, path, name, volumeId, bufferSize, initialPages, extensionPages,
                        maximumPages, false, false, false);
            }
            if (oldExchange != null && oldExchange.getVolume().equals(_volume)) {
                _exchange = oldExchange;
            }
        }

        public void handleTreeIdRecord(String treeName) throws PersistitException {
            Exchange oldExchange = _exchange;
            _exchange = null;
            _tree = null;

            if (_volume == null) {
                return;
            }

            if (!_treeSelector.isTreeNameSelected(_volume.getName(), treeName)) {
                return;
            }

            _tree = _volume.getTree(treeName, _createMissingVolumes | _createMissingTrees);

            if (oldExchange != null && oldExchange.getTree() == _tree) {
                _exchange = oldExchange;
            }
            _keyFilter = _treeSelector.keyFilter(_volume.getName(), treeName);

        }

        public void handleTimeStampRecord(long timeStamp) throws PersistitException {
        }

        public void handleHostNameRecord(String hostName) throws PersistitException {
        }

        public void handleUserRecord(String userName) throws PersistitException {
        }

        public void handleCommentRecord(String comment) throws PersistitException {
        }

        public void handleCountRecord(long keyValueRecords, long otherRecords) throws PersistitException {
        }

        public void handleStartRecord() throws PersistitException {
        }

        public void handleEndRecord() throws PersistitException {
        }

        public void handleExceptionRecord(String exceptionString) throws PersistitException {
        }

        public void handleCompletionRecord() throws PersistitException {
        }

    }

    @Override
    public void runTask() throws Exception {
        _dis = new DataInputStream(new BufferedInputStream(new FileInputStream(_filePath), DEFAULT_BUFFER_SIZE));
        load(_treeSelector, _createMissingVolumes, _createMissingTrees);
    }

    @Override
    public String getStatus() {
        if (_handler == null || _handler._tree == null) {
            return null;
        }
        Tree tree = _handler._tree;
        return tree.getName() + " in " + tree.getVolume().getPath() + " (" + _dataRecordCount + ")";
    }

}
