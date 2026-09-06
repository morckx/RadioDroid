package net.programmierecke.radiodroid2.players.exoplayer;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Time-shift ring buffer for live radio (rewind feature).
 *
 * <p>Wraps {@link IcyDataSource}. A single background reader thread continuously pulls the
 * live stream into a fixed-size ring buffer (retaining the most recent {@code capacityBytes},
 * i.e. exactly the raw bytes ExoPlayer would otherwise read, ICY metadata frames included).
 * ExoPlayer's own {@link #read} always serves from the ring at its current read position,
 * which may lag behind the live edge after a backward seek.
 *
 * <p>The key property: the live network read never stops, so rewinding and later catching
 * back up to live is seamless — there is no reconnect and no ICY-metadata re-alignment, which
 * a naive "reopen at position" approach would break because {@link IcyDataSource#open} ignores
 * {@link DataSpec#position} and always reconnects at the live edge.
 *
 * <p>Positions are absolute byte offsets from the start of the single live connection.
 * ExoPlayer only seeks within [oldestRetainedBytePos, liveBytePos], the window we expose.
 */
@UnstableApi
public class RingBufferDataSource implements DataSource {

    private static final String TAG = "RingBufferDataSource";

    private final IcyDataSource delegate;
    private final byte[] ring;
    private final int capacityBytes;

    private final Object lock = new Object();

    /** How many bytes the live stream has produced so far (the live edge). Guarded by lock. */
    private long liveBytePos = 0;

    /** Oldest byte position still retained in the ring. Guarded by lock. */
    private long oldestRetainedBytePos = 0;

    /** ExoPlayer's current read position within the exposed window. Guarded by lock. */
    private long readBytePos = 0;

    /**
     * When >= 0, the position the next open() should read from instead of the DataSpec's
     * position. Set by {@link #rewindBy} so a player restart resumes from the rewound point
     * even though the (unseekable) stream makes ExoPlayer re-open at position 0. Guarded by lock.
     */
    private long pendingReadPos = -1;

    // Byte-rate measurement, for mapping a rewind duration to a byte offset. Guarded by lock.
    private long firstByteWallClockMs = 0;

    private volatile boolean started = false;
    private volatile boolean closed = false;
    private volatile IOException readerError = null;
    private Thread readerThread;

    public RingBufferDataSource(IcyDataSource delegate, int capacityBytes) {
        this.delegate = delegate;
        this.capacityBytes = capacityBytes;
        this.ring = new byte[capacityBytes];
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        final long requestedPos = dataSpec.position;
        Log.i(TAG, "open() requestedPos=" + requestedPos + " live=" + liveBytePos
                + " oldest=" + oldestRetainedBytePos + " started=" + started);

        if (!started) {
            // First open: connect the live stream and start the background reader.
            long result = delegate.open(dataSpec);
            synchronized (lock) {
                liveBytePos = requestedPos;
                oldestRetainedBytePos = requestedPos;
                readBytePos = requestedPos;
            }
            startReaderThread();
            started = true;
            return result;
        }

        // Subsequent open. The stream is unseekable, so on a rewind ExoPlayer restarts and
        // re-opens at position 0; we override that with the pending rewind position when set.
        synchronized (lock) {
            long desired = (pendingReadPos >= 0) ? pendingReadPos : requestedPos;
            pendingReadPos = -1;
            long clamped = Math.max(oldestRetainedBytePos, Math.min(desired, liveBytePos));
            if (clamped != desired) {
                Log.w(TAG, "open(): desired " + desired + " clamped to " + clamped
                        + " (window " + oldestRetainedBytePos + ".." + liveBytePos + ")");
            }
            readBytePos = clamped;
            Log.i(TAG, "open(): read cursor set to " + clamped
                    + " (" + (liveBytePos - clamped) + " bytes behind live)");
        }
        return C.LENGTH_UNSET;
    }

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            byte[] tmp = new byte[16 * 1024];
            while (!closed) {
                int n;
                try {
                    n = delegate.read(tmp, 0, tmp.length);
                } catch (IOException e) {
                    readerError = e;
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                    return;
                }
                if (n == C.RESULT_END_OF_INPUT || n < 0) {
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                    return;
                }
                if (n > 0) {
                    synchronized (lock) {
                        appendToRing(tmp, 0, n);
                        lock.notifyAll();
                    }
                }
            }
        }, "RadioRingBufferReader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) {
            return 0;
        }
        synchronized (lock) {
            while (true) {
                if (readerError != null) {
                    throw readerError;
                }
                long available = liveBytePos - readBytePos;
                if (available > 0) {
                    int toCopy = (int) Math.min(readLength, available);
                    // Guard against the reader having advanced oldestRetainedBytePos past us.
                    if (readBytePos < oldestRetainedBytePos) {
                        readBytePos = oldestRetainedBytePos;
                        available = liveBytePos - readBytePos;
                        toCopy = (int) Math.min(readLength, available);
                    }
                    int ringStart = (int) (readBytePos % capacityBytes);
                    int firstChunk = Math.min(toCopy, capacityBytes - ringStart);
                    System.arraycopy(ring, ringStart, buffer, offset, firstChunk);
                    if (firstChunk < toCopy) {
                        System.arraycopy(ring, 0, buffer, offset + firstChunk, toCopy - firstChunk);
                    }
                    readBytePos += toCopy;
                    return toCopy;
                }
                if (closed) {
                    return C.RESULT_END_OF_INPUT;
                }
                // Caught up to the live edge: wait for the reader to produce more.
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for live data", e);
                }
            }
        }
    }

    /**
     * Rewind the read cursor by {@code ms} of audio and return how many ms we actually moved
     * back (bounded by what is retained). The read position is applied on the player's next
     * open() via {@link #pendingReadPos}. Uses the measured byte-rate to map ms to bytes.
     */
    public long rewindBy(long ms) {
        synchronized (lock) {
            long bytesPerSecond = measuredBytesPerSecond();
            if (bytesPerSecond <= 0) {
                Log.w(TAG, "rewindBy: no byte-rate yet, cannot rewind");
                return 0;
            }
            long rewindBytes = bytesPerSecond * ms / 1000;
            long target = Math.max(oldestRetainedBytePos, readBytePos - rewindBytes);
            long movedBytes = readBytePos - target;
            pendingReadPos = target;
            long movedMs = movedBytes * 1000 / bytesPerSecond;
            Log.i(TAG, "rewindBy: requested " + ms + "ms (" + rewindBytes + " bytes @ "
                    + bytesPerSecond + " B/s), moved " + movedMs + "ms to pos " + target);
            return movedMs;
        }
    }

    /** Estimated average bytes/second since the stream started. Caller holds lock. */
    private long measuredBytesPerSecond() {
        if (firstByteWallClockMs == 0) {
            return 0;
        }
        long elapsedMs = System.currentTimeMillis() - firstByteWallClockMs;
        if (elapsedMs < 1000) {
            return 0; // not enough data yet for a stable estimate
        }
        // liveBytePos counts total bytes received (initial position is 0 for a live stream).
        return liveBytePos * 1000 / elapsedMs;
    }

    /** Append live bytes to the ring, advancing the live edge. Caller holds lock. */
    private void appendToRing(byte[] buffer, int offset, int length) {
        if (firstByteWallClockMs == 0) {
            firstByteWallClockMs = System.currentTimeMillis();
        }
        if (length >= capacityBytes) {
            System.arraycopy(buffer, offset + length - capacityBytes, ring, 0, capacityBytes);
            liveBytePos += length;
            oldestRetainedBytePos = liveBytePos - capacityBytes;
            return;
        }
        int ringStart = (int) (liveBytePos % capacityBytes);
        int firstChunk = Math.min(length, capacityBytes - ringStart);
        System.arraycopy(buffer, offset, ring, ringStart, firstChunk);
        if (firstChunk < length) {
            System.arraycopy(buffer, offset + firstChunk, ring, 0, length - firstChunk);
        }
        liveBytePos += length;
        if (liveBytePos - oldestRetainedBytePos > capacityBytes) {
            oldestRetainedBytePos = liveBytePos - capacityBytes;
        }
    }

    /** How many ms behind live the read cursor currently is, given an estimated byte rate. */
    public long behindLiveBytes() {
        synchronized (lock) {
            return liveBytePos - readBytePos;
        }
    }

    /** How many bytes are currently retained and available to rewind into. */
    public long getRetainedBytes() {
        synchronized (lock) {
            return liveBytePos - oldestRetainedBytePos;
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return delegate.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return delegate.getResponseHeaders();
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        delegate.addTransferListener(transferListener);
    }

    @Override
    public void close() throws IOException {
        // IMPORTANT: media3 calls close() between seeks on the same DataSource instance
        // (ProgressiveMediaPeriod cancels the current load and starts a new one). We must
        // NOT stop the background reader or the live connection here, otherwise a rewind
        // would kill live playback. The next open() simply repositions the read cursor.
        // Full teardown happens in release(), called by the player when playback ends.
        Log.i(TAG, "close() (seek/load boundary) - reader kept alive");
    }

    /**
     * Fully tear down: stop the background reader and close the live connection. Called by
     * {@link ExoPlayerWrapper} when it stops/pauses/releases the player, since a plain
     * DataSource has no separate release hook and close() is reused for seeks.
     */
    public void release() {
        closed = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        try {
            delegate.close();
        } catch (IOException e) {
            Log.w(TAG, "Error closing delegate on release", e);
        }
    }
}
