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

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.List;

import com.persistit.TimestampAllocator.Checkpoint;
import com.persistit.exception.PersistitException;
import com.persistit.exception.PersistitIOException;

/**
 * Abstract superclass of any object that needs transactional semantics while
 * maintaining state information in memory. For example, a concrete
 * implementation might maintain a statistical aggregation based on the contents
 * of a Persistit database. This abstract superclass provides the mechanism to
 * ensure the consistency of this state.
 * <p>
 * An application registers an instance TC of this class with Persistit prior to
 * calling the {@link Persistit#initialize()} method. During Persisit's startup
 * and recovery processing, the state of TC is modified to include the effects
 * of all previously committed transactions. During normal operation, code
 * performed within the scope of a Transaction calls methods of TC to read
 * and/or modify its state. The modifications remain private to the executing
 * transaction until the transaction commits. At that time the modifications are
 * record on the Persistit Journal and applied to a globally visible version of
 * TC's state. Operations that read from TC state are recorded in the
 * transaction's read set and verified for consistency during commit processing.
 * <p>
 * 
 * @author peter
 * 
 */
public abstract class TransactionalCache {

    private final static Update SAVED = new ReloadUpdate();

    final protected Persistit _persistit;

    protected Checkpoint _checkpoint;

    protected TransactionalCache _previousVersion;

    protected TransactionalCache(final Persistit persistit) {
        _persistit = persistit;
    }

    protected abstract Update createUpdate(final byte opCode);

    public abstract static class Update {
        final byte _opCode;

        private Update() {
            _opCode = 0;
        }

        protected Update(byte opCode) {
            if (opCode == 0) {
                throw new IllegalArgumentException();
            }
            _opCode = opCode;
        }

        /**
         * Compute the number of bytes required to serialize this update, not
         * including the opcode. This method may return an overestimate.
         * 
         * @return number of bytes to reserve for serialization.
         */
        protected abstract int size();

        /**
         * Serialize this Update to an underlying ByteBuffer. Subclasses should
         * override {@link #writeArg(ByteBuffer)} to efficiently record the
         * argument value.
         * 
         * @param bb
         * @throws IOException
         */
        final void write(final ByteBuffer bb) throws IOException {
            bb.put(_opCode);
            writeArg(bb);
        }

        /**
         * Serialize the argument value to the supplied ByteBuffer. This is a
         * generic implementation that requires the argument value to be
         * non-null and serializable. Subclasses may override with more
         * efficient implementations.
         * 
         * @param bb
         * @throws IOException
         */
        protected abstract void writeArg(final ByteBuffer bb)
                throws IOException;

        /**
         * Deserialize the argument value from the supplied ByteBuffer.
         * 
         * @param bb
         * @throws IOException
         */
        protected abstract void readArg(final ByteBuffer bb) throws IOException;

        /**
         * Attempt to combine this Update with a previously record update. For
         * example suppose the supplied Update and this Update each add 1 to the
         * same counter. Then this method modifies the supplied Update to add 2
         * and returns <code>true</code> to signify that this Update should not
         * be added to the pending update queue.
         * <P>
         * Default implementation does nothing and returns <code>false</code>.
         * Subclasses may override to provide more efficient behavior.
         * 
         * @param previous
         * @return <code>true</code> if this Update was successfully combined
         *         with <code>previous</code>.
         */
        protected boolean combine(final Update previous) {
            return false;
        }

        /**
         * Attempt to cancel this update with a previous update. For example,
         * suppose the supplied Update increments a counter, and this Update
         * decrements the same counter. Then this method could return
         * <code>true</code> to signify that both Updates can be removed from
         * the pending update queue.
         * <p>
         * Default implementation does nothing and returns <code>false</code>.
         * Subclasses may override to provide more efficient behavior.
         * 
         * @param previous
         * @return <code>true</code> if this Update successfully canceled the
         */
        protected boolean cancel(final Update previous) {
            return false;
        }

        /**
         * Apply the update to the state of the supplied TransactionalCache.
         * This method is called during commit processing.
         */
        protected abstract void apply(final TransactionalCache tc);
    }

    public abstract static class UpdateObject extends Update {

        protected UpdateObject(byte opCode) {
            super(opCode);
        }

        /**
         * Compute the number of bytes required to serialize this update, not
         * including the opcode. This method may return an overestimate.
         * 
         * @return number of bytes to reserve for serialization.
         */
        protected abstract int size();

        /**
         * Serialize the argument value to the supplied ByteBuffer. This is a
         * generic implementation that requires the argument value to be
         * non-null and serializable. Subclasses may override with more
         * efficient implementations.
         * 
         * @param bb
         * @throws IOException
         */
        protected void writeArg(final ByteBuffer bb) throws IOException {
            Serializable s = (Serializable) getArg();
            new ObjectOutputStream(new OutputStream() {

                @Override
                public void write(int b) throws IOException {
                    bb.put((byte) b);
                }

                @Override
                public void write(byte[] src, int offset, int length)
                        throws IOException {
                    bb.put(src, offset, length);
                }

            }).writeObject(s);
        }

        /**
         * Deserialize the argument value from the supplied ByteBuffer. This is
         * a generic implementation that assume the value was written through
         * default Java serialization. Subclasses may override with more
         * efficient implementations.
         * 
         * @param bb
         * @throws IOException
         */
        protected void readArg(final ByteBuffer bb) throws IOException {
            try {
                setArg(new ObjectInputStream(new InputStream() {

                    @Override
                    public int read() throws IOException {
                        return bb.get() & 0xFF;
                    }

                    @Override
                    public int read(final byte[] bytes, final int offset,
                            final int length) throws IOException {
                        bb.get(bytes, offset, length);
                        return length;
                    }

                }).readObject());
            } catch (SecurityException e) {
                throw new IOException(e);
            } catch (ClassNotFoundException e) {
                throw new IOException(e);
            }
        }

        /**
         * Implementation required only for default object serialization.
         * 
         * @return the argument value as an Object.
         */
        protected abstract Object getArg();

        /**
         * Implementation required only for default object serialization. Set
         * the argument to the supplied Object.
         * 
         * @param arg
         */
        protected abstract void setArg(Object arg);

    }

    /**
     * Abstract superclass of any Updates that holds a single int-valued
     * argument. This implements provides serialization code optimized for this
     * case.
     */
    public abstract static class UpdateInt extends Update {
        protected int _arg;

        protected UpdateInt(byte opCode) {
            super(opCode);
        }

        @Override
        protected void writeArg(final ByteBuffer bb) throws IOException {
            bb.putInt(_arg);
        }

        @Override
        protected void readArg(final ByteBuffer bb) throws IOException {
            _arg = bb.getInt();
        }

        @Override
        protected int size() {
            return 4;
        }
    }

    /**
     * Abstract superclass of any Update that holds a single long-valued
     * argument. This subclass provides serialization code optimized for this
     * case.
     */
    public abstract static class UpdateLong extends Update {
        protected long _arg;

        protected UpdateLong(byte opCode) {
            super(opCode);
        }

        @Override
        protected void writeArg(final ByteBuffer bb) throws IOException {
            bb.putLong(_arg);
        }

        @Override
        protected void readArg(final ByteBuffer bb) throws IOException {
            _arg = bb.getLong();
        }

        @Override
        protected int size() {
            return 8;
        }
    }

    /**
     * Abstract superclass of any Update that holds its argument in the form of
     * an array of up to 65535 bytes. This subclass provides serialization code
     * optimized for this case.
     */
    public abstract static class UpdateByteArray extends Update {
        protected byte[] _args;

        protected UpdateByteArray(byte opCode) {
            super(opCode);
        }

        @Override
        protected void writeArg(final ByteBuffer bb) throws IOException {
            bb.putChar((char) _args.length);
            for (int index = 0; index < _args.length; index++) {
                bb.put(_args[index]);
            }
        }

        @Override
        protected void readArg(final ByteBuffer bb) throws IOException {
            int length = bb.getChar();
            _args = new byte[length];
            for (int index = 0; index < _args.length; index++) {
                _args[index] = bb.get();
            }
        };

    }

    /**
     * Abstract superclass of any Update that holds its argument in the form of
     * an array of up to 65535 ints. This subclass provides serialization code
     * optimized for this case.
     */
    public abstract static class UpdateIntArray extends Update {
        protected int[] _args;

        protected UpdateIntArray(byte opCode) {
            super(opCode);
        }

        @Override
        protected void writeArg(final ByteBuffer bb) throws IOException {
            bb.putChar((char) _args.length);
            for (int index = 0; index < _args.length; index++) {
                bb.putInt(_args[index]);
            }
        }

        @Override
        protected void readArg(final ByteBuffer bb) throws IOException {
            int length = bb.getChar();
            _args = new int[length];
            for (int index = 0; index < _args.length; index++) {
                _args[index] = bb.getInt();
            }
        };

        @Override
        protected int size() {
            return _args.length * 4 + 2;
        }
    }

    /**
     * Abstract superclass of any Update that holds its argument in the form of
     * an array of up to 65535 longs. This subclass provides serialization code
     * optimized for this case.
     */
    public abstract static class UpdateLongArray extends Update {
        protected long[] _args;

        protected UpdateLongArray(byte opCode) {
            super(opCode);
        }

        @Override
        protected void writeArg(final ByteBuffer bb) throws IOException {
            bb.putChar((char) _args.length);
            for (int index = 0; index < _args.length; index++) {
                bb.putLong(_args[index]);
            }
        }

        @Override
        protected void readArg(final ByteBuffer bb) throws IOException {
            int length = bb.getChar();
            _args = new long[length];
            for (int index = 0; index < _args.length; index++) {
                _args[index] = bb.getLong();
            }
        };

        @Override
        protected int size() {
            return _args.length * 8 + 2;
        }
    }

    /**
     * Abstract superclass of any Update that holds its argument in the form of
     * an array of up to 65535 longs. This subclass provides serialization code
     * optimized for this case.
     */
    public static class ReloadUpdate extends Update {

        protected ReloadUpdate() {
            super();
        }

        @Override
        protected void writeArg(final ByteBuffer bb) throws IOException {

        }

        @Override
        protected void readArg(final ByteBuffer bb) throws IOException {

        };

        @Override
        protected int size() {
            return 0;
        }

        @Override
        protected void apply(TransactionalCache tc) {
            // Does nothing during normal processing - causes
            // reload from saved checkpoint during recovery
        }
    }

    /**
     * Return a globally unique serial number for a specific instance of a
     * <code>TransactionalCache</code> implementation. The value of
     * serialVersionUID generated for Java serialization is recommended. This
     * value is used during recovery processing to direct Update records to
     * particular cache instances.
     * 
     * @return a unique ID value
     */
    protected abstract long cacheId();

    /**
     * Inserts the supplied {@link TransactionalCache#Update)} into the update
     * queue for this transaction. Subclasses provide convenience methods to
     * generate and enqueue updates. This method attempts to cancel or combine
     * the supplied <code>Update</code> with the previously enqueued
     * <code>Update</code>, and so may result in either removing or modifying
     * the previous <code>Update</code> rather than adding a new record.
     * 
     * @param update
     */
    protected final void update(final Update update) {
        final Transaction transaction = _persistit.getTransaction();
        if (!transaction.isActive()) {
            throw new IllegalStateException(
                    "TransactionalCache may be updated only within a transaction");
        }
        final List<Update> updates = transaction.updateList(this);
        if (!updates.isEmpty()) {
            final Update u = updates.get(updates.size() - 1);
            if (update.cancel(u)) {
                updates.remove(updates.size() - 1);
                return;
            } else if (update.combine(u)) {
                return;
            }
        }
        updates.add(update);
    }

    /**
     * Commit all the {@link TransactionalCache#Update} records. As a
     * side-effect, this method may create a pre-checkpoint copy of this
     * <code>TransactionalCache</code> to contain only values Updated prior to
     * the checkpoint.
     */
    final boolean commit(final Transaction transaction) {
        final List<Update> updates = transaction.updateList(this);
        if (updates.isEmpty()) {
            return false;
        }
        final long timestamp = transaction.getCommitTimestamp();
        if (timestamp == -1) {
            throw new IllegalStateException("Must be called from doCommit");
        }
        final Checkpoint checkpoint = _persistit.getCurrentCheckpoint();
        if (_checkpoint == null) {
            _checkpoint = checkpoint;
        } else if (checkpoint.getTimestamp() > _checkpoint.getTimestamp()) {
            _previousVersion = copy();
            _checkpoint = checkpoint;
        }
        TransactionalCache tc = this;
        while (tc != null) {
            for (int index = 0; index < updates.size(); index++) {
                updates.get(index).apply(tc);
            }
            if (timestamp > tc._checkpoint.getTimestamp()) {
                break;
            }
            tc = tc._previousVersion;
        }
        updates.clear();
        return true;
    }

    final void recoverUpdates(final ByteBuffer bb) throws PersistitException {
        while (bb.hasRemaining()) {
            final byte opCode = bb.get();
            if (opCode == 0) {
                load();
            } else {
                final Update update = createUpdate(opCode);
                try {
                    update.readArg(bb);
                    update.apply(this);
                } catch (IOException e) {
                    throw new PersistitIOException(e);
                }
            }
        }
    }

    /**
     * Get the version of this <code>TransactionalCache</code> that was valid at
     * the specified timestamp.
     * 
     * @param checkpoint
     * @return
     */
    final TransactionalCache version(final Checkpoint checkpoint) {
        TransactionalCache tc = this;
        while (tc != null) {
            if (tc._checkpoint.getTimestamp() <= checkpoint.getTimestamp()) {
                return tc;
            }
            tc = tc._previousVersion;
        }
        return tc;
    }

    final void save(final Checkpoint checkpoint) throws PersistitException {
        TransactionalCache tc = this;
        TransactionalCache newer = null;
        while (tc != null) {
            if (tc._checkpoint != null
                    && tc._checkpoint.getTimestamp() <= checkpoint
                            .getTimestamp()) {
                tc.save();
                update(SAVED);
                if (newer != null) {
                    newer._previousVersion = null;
                }
            }
            newer = tc;
            tc = tc._previousVersion;
        }
    }

    public final void register() {
        _persistit.addTransactionalCache(this);
    }

    /**
     * Construct a copy of this <code>TransactionalCache</code>. Any data
     * structures used to hold state information must be deep-copied. The copy
     * will be pinned to its declared checkpoint and will receive no updates
     * issued by transactions committing after that checkpoint, while the
     * original TransactionalCache will continue to receive new updates.
     * 
     * @return the copy
     */
    protected abstract TransactionalCache copy();

    /**
     * Save the state of this <code>TransactionalCache</code> such that it will
     * be recoverable, typically by writing its state to backing store in
     * Persistit trees.
     */
    protected abstract void save() throws PersistitException;

    /**
     * Load the state of this <code>TransactionalCache</code>, typically from
     * backing store in Persistit trees.
     */
    protected abstract void load() throws PersistitException;

}
