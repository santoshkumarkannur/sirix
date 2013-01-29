/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the
 * names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.sirix.page;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sirix.api.PageReadTrx;
import org.sirix.api.PageWriteTrx;
import org.sirix.exception.SirixException;
import org.sirix.node.interfaces.Record;
import org.sirix.node.interfaces.RecordPersistenter;
import org.sirix.page.interfaces.KeyValuePage;
import org.sirix.page.interfaces.Page;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

/**
 * <h1>UnorderedKeyValuePage</h1>
 * 
 * <p>
 * An UnorderedKeyValuePage stores a set of records, commonly nodes in an
 * unordered datastructure.
 * </p>
 */
public final class UnorderedKeyValuePage implements KeyValuePage<Long, Record> {

	/** Key of record page. This is the base key of all contained nodes. */
	private final long mRecordPageKey;

	/** Records. */
	private final Map<Long, Record> mRecords;

	/** Key <=> Offset/Byte-array mapping. */
	private final Map<Long, byte[]> mSlots;

	/** Determine if node page has been modified. */
	private boolean mIsDirty;

	/** Sirix {@link PageReadTrx}. */
	private final PageReadTrx mPageReadTrx;

	/** The kind of page (in which subtree it resides). */
	private final PageKind mPageKind;

	/** Persistenter. */
	private final RecordPersistenter mPersistenter;
	
	/** Stored records/slots. */
	private int mSize;

	/**
	 * Create record page.
	 * 
	 * @param recordPageKey
	 *          base key assigned to this node page
	 * @param pageKind
	 *          the kind of subtree page (NODEPAGE, PATHSUMMARYPAGE,
	 *          TEXTVALUEPAGE, ATTRIBUTEVALUEPAGE)
	 * @param pageReadTrx
	 *          the page reading transaction
	 */
	public UnorderedKeyValuePage(final @Nonnegative long recordPageKey,
			final @Nonnull PageKind pageKind, final @Nonnull PageReadTrx pageReadTrx) {
		// Assertions instead of checkNotNull(...) checks as it's part of the
		// internal flow.
		assert recordPageKey >= 0 : "recordPageKey must not be negative!";
		assert pageReadTrx != null : "pageReadTrx must not be null!";
		mRecordPageKey = recordPageKey;
		mRecords = new LinkedHashMap<>();
		mIsDirty = true;
		mPageReadTrx = pageReadTrx;
		mPageKind = pageKind;
		mSlots = new HashMap<>();
		mPersistenter = pageReadTrx.getSession().getResourceConfig().mPersistenter;
	}

	/**
	 * Read node page.
	 * 
	 * @param in
	 *          input bytes to read page from
	 * @param pageReadTrx
	 *          {@link 
	 */
	protected UnorderedKeyValuePage(final @Nonnull ByteArrayDataInput in,
			final @Nonnull PageReadTrx pageReadTrx) {
		mRecordPageKey = in.readLong();
		final int size = in.readInt();
		mRecords = new LinkedHashMap<>(size);
		mPersistenter = pageReadTrx.getSession().getResourceConfig().mPersistenter;
		mSlots = new HashMap<>(size);
		for (int index = 0; index < size; index++) {
			final long key = in.readLong();
			final int length = in.readInt();
			final byte[] payload = new byte[length];
			in.readFully(payload);
			mSlots.put(key, payload);
		}
		assert pageReadTrx != null : "pageReadTrx must not be null!";
		mPageReadTrx = pageReadTrx;
		mPageKind = PageKind.getKind(in.readByte());
		mSize = size;
	}

	@Override
	public long getPageKey() {
		return mRecordPageKey;
	}

	@Override
	public Record getValue(final @Nonnull Long key) {
		assert key != null : "key must not be null!";
		Record record = mRecords.get(key);
		if (record == null) {
			final byte[] in = mSlots.get(key);
			if (in != null) {
				record = mPersistenter.deserialize(ByteStreams.newDataInput(in),
						mPageReadTrx);
				mRecords.put(key, record);
				mSlots.remove(key);
			}
		}
		return record;
	}

	/**
	 * {@inheritDoc}
	 * 
	 * The key is not used as it is implicitly in the value (the record ID).
	 */
	@Override
	public void setEntry(final @Nullable Long key, final @Nonnull Record value) {
		assert value != null : "record must not be null!";
		if (!mRecords.containsKey(key)) {
			if (mSlots.containsKey(key)) {
				mSlots.remove(key);
				mSize--;
			}
			mSize++;
		}
		mRecords.put(value.getNodeKey(), value);
	}

	@Override
	public void serialize(final @Nonnull ByteArrayDataOutput out) {
		out.writeLong(mRecordPageKey);
		out.writeInt(mSize);
		final RecordPersistenter persistenter = mPageReadTrx.getSession()
				.getResourceConfig().mPersistenter;
		for (final Map.Entry<Long, byte[]> entry : mSlots.entrySet()) {
			out.writeLong(entry.getKey());
			final byte[] data = entry.getValue();
			final int length = data.length;
			out.writeInt(length);
			out.write(data);
		}
		for (final Record node : mRecords.values()) {
			if (mSlots.get(node.getNodeKey()) == null) {
				out.writeLong(node.getNodeKey());
				final ByteArrayDataOutput output = ByteStreams.newDataOutput();
				persistenter.serialize(output, node, mPageReadTrx);
				final byte[] data = output.toByteArray();
				out.writeInt(data.length);
				out.write(data);
			}
		}
		out.writeByte(mPageKind.getID());
	}

	@Override
	public String toString() {
		final ToStringHelper helper = Objects.toStringHelper(this)
				.add("pagekey", mRecordPageKey).add("nodes", mRecords.toString());
		for (final Record node : mRecords.values()) {
			helper.add("node", node);
		}
		return helper.toString();
	}

	@Override
	public Set<Entry<Long, Record>> entrySet() {
//		deserializeAll();
		return mRecords.entrySet();
	}

	// Deserialize all records.
	private void deserializeAll() {
		for (final Map.Entry<Long, byte[]> entry : mSlots.entrySet()) {
			final Long key = entry.getKey();
			if (mRecords.get(key) == null) {
				mRecords.put(
						key,
						mPersistenter.deserialize(
								ByteStreams.newDataInput(entry.getValue()), mPageReadTrx));
				mSlots.remove(key);
			}
		}
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(mRecordPageKey, mRecords, mSlots);
	}

	@Override
	public boolean equals(final @Nullable Object obj) {
		if (obj instanceof UnorderedKeyValuePage) {
			final UnorderedKeyValuePage other = (UnorderedKeyValuePage) obj;
			return mRecordPageKey == other.mRecordPageKey
					&& Objects.equal(mRecords, other.mRecords)
					&& Objects.equal(mSlots, other.mSlots);
		}
		return false;
	}

	@Override
	public PageReference[] getReferences() {
		throw new UnsupportedOperationException();
	}

	@Override
	public <K extends Comparable<? super K>, V extends Record, S extends KeyValuePage<K, V>> void commit(
			PageWriteTrx<K, V, S> pPageWriteTrx) throws SirixException {
	}

	@Override
	public Collection<Record> values() {
//		deserializeAll();
		return mRecords.values();
	}

	@Override
	public PageReference getReference(int offset) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isDirty() {
		return mIsDirty;
	}

	@Override
	public Page setDirty(final boolean dirty) {
		mIsDirty = dirty;
		return this;
	}

	@Override
	public PageReadTrx getPageReadTrx() {
		return mPageReadTrx;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <C extends KeyValuePage<Long, Record>> C newInstance(
			final long recordPageKey, final @Nonnull PageKind pageKind,
			final @Nonnull PageReadTrx pageReadTrx) {
		return (C) new UnorderedKeyValuePage(recordPageKey, pageKind, pageReadTrx);
	}

	@Override
	public PageKind getPageKind() {
		return mPageKind;
	}

	@Override
	public void setSlot(final @Nonnull Long key, final @Nonnull byte[] in) {
		if (!mSlots.containsKey(key)) {
			if (mRecords.containsKey(key)) {
				mRecords.remove(key);
				mSize--;
			}
			mSize++;
		}
		mSlots.put(key, in);
	}

	@Override
	public byte[] getSlotValue(final @Nonnull Long key) {
		return mSlots.get(key);
	}

	@Override
	public Set<Entry<Long, byte[]>> slotEntrySet() {
		return mSlots.entrySet();
	}

	@Override
	public int size() {
		return mSize;
	}
}
