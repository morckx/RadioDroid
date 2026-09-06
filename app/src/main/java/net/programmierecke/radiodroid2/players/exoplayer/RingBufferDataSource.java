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

    // --- ICY metadata frame alignment ---
    // The raw bytes we retain include ICY metadata frames every `metaint` audio bytes:
    //   [metaint audio bytes][1 length byte L][L*16 metadata bytes] repeating.
    // After a rewind we restart ExoPlayer's IcyExtractor via seekTo(0); it then expects the
    // replayed bytes to begin exactly at an audio-segment start (right after a metadata
    // frame). If we start mid-segment, its frame boundaries land in audio -> garbage/clicks.
    // So we parse the frame structure as bytes arrive and record each audio-segment-start
    // offset, then snap any rewind target back to the nearest such boundary.
    private int metaint = 0;               // 0 => unknown / no ICY metadata
    private long nextSegmentBoundaryPos = 0; // absolute pos of the next audio-segment start
    private int metaParseState = 0;        // 0=in audio, 1=expect length byte, 2=in metadata
    private int metaBytesRemaining = 0;    // audio or metadata bytes left in current phase
    // Sorted-by-construction list of recent audio-segment-start offsets (absolute positions).
    private final java.util.ArrayDeque<Long> segmentBoundaries = new java.util.ArrayDeque<>();

    private volatile boolean started = false;
    private volatile boolean closed = false;
    private volatile IOException readerError = null;
    private Thread readerThread;

    public RingBufferDataSource(IcyDataSource delegate, int capacityBytes) {
        this.delegate = delegate;
        this.capacityBytes = capacityBytes;
        this.ring = new byte[capacityBytes];
    }

    /**
     * Provide the ICY metadata interval (bytes of audio between metadata frames) so the ring
     * can track frame boundaries for rewind alignment. Called once metadata size is known.
     * 0 disables alignment (no ICY metadata in the stream).
     */
    public void setMetaint(int metaint) {
        synchronized (lock) {
            this.metaint = metaint;
            if (metaint > 0) {
                // The stream begins with an audio segment.
                metaParseState = 0;
                metaBytesRemaining = metaint;
                nextSegmentBoundaryPos = liveBytePos;
                segmentBoundaries.clear();
                segmentBoundaries.addLast(liveBytePos);
            }
        }
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
            try {
                while (!closed) {
                    int n;
                    try {
                        n = delegate.read(tmp, 0, tmp.length);
                    } catch (IOException e) {
                        if (!closed) {
                            readerError = e;
                        }
                        break;
                    }
                    if (n == C.RESULT_END_OF_INPUT || n < 0) {
                        break;
                    }
                    if (n > 0) {
                        synchronized (lock) {
                            appendToRing(tmp, 0, n);
                            lock.notifyAll();
                        }
                    }
                }
            } finally {
                // This thread owns the delegate connection; close it here (off the main
                // thread) so the SSL socket close's network I/O never runs on the caller's
                // thread (avoids NetworkOnMainThreadException from release()).
                try {
                    delegate.close();
                } catch (IOException e) {
                    Log.w(TAG, "Error closing delegate in reader thread", e);
                }
                synchronized (lock) {
                    lock.notifyAll();
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

            // Snap the target to an ICY audio-segment boundary so ExoPlayer's IcyExtractor
            // stays aligned after the restart (otherwise metadata frames land in audio ->
            // clicks/garbage). If no boundary info (non-ICY stream), use the raw target.
            if (metaint > 0) {
                long snapped = snapToSegmentBoundary(target);
                if (snapped >= 0) {
                    target = snapped;
                } else {
                    Log.w(TAG, "rewindBy: no segment boundary <= target, using oldest retained");
                    target = segmentBoundaries.isEmpty() ? oldestRetainedBytePos
                            : segmentBoundaries.peekFirst();
                }
            }

            long movedBytes = readBytePos - target;
            pendingReadPos = target;
            long movedMs = movedBytes * 1000 / bytesPerSecond;
            Log.i(TAG, "rewindBy: requested " + ms + "ms (" + rewindBytes + " bytes @ "
                    + bytesPerSecond + " B/s), moved " + movedMs + "ms to pos " + target
                    + " (snapped, metaint=" + metaint + ")");
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

    /**
     * Walk newly-arrived raw bytes through the ICY metaint state machine, recording the
     * absolute position of each audio-segment start (a valid rewind restart point).
     * Caller holds lock. {@code startPos} is the absolute position of buffer[offset].
     */
    private void trackFrameBoundaries(byte[] buffer, int offset, int length, long startPos) {
        if (metaint <= 0) {
            return;
        }
        for (int i = 0; i < length; i++) {
            long pos = startPos + i;
            switch (metaParseState) {
                case 0: // audio phase
                    if (--metaBytesRemaining == 0) {
                        metaParseState = 1; // next byte is the metadata length
                    }
                    break;
                case 1: // length byte
                    int lenByte = buffer[offset + i] & 0xFF;
                    if (lenByte == 0) {
                        // Empty metadata frame: next audio segment starts at pos+1.
                        recordSegmentStart(pos + 1);
                        metaParseState = 0;
                        metaBytesRemaining = metaint;
                    } else {
                        metaParseState = 2;
                        metaBytesRemaining = lenByte * 16;
                    }
                    break;
                case 2: // metadata bytes
                    if (--metaBytesRemaining == 0) {
                        // Metadata frame ended; the next byte starts an audio segment.
                        recordSegmentStart(pos + 1);
                        metaParseState = 0;
                        metaBytesRemaining = metaint;
                    }
                    break;
            }
        }
    }

    private void recordSegmentStart(long pos) {
        segmentBoundaries.addLast(pos);
        // Drop boundaries that have fallen out of the retained window.
        while (!segmentBoundaries.isEmpty() && segmentBoundaries.peekFirst() < oldestRetainedBytePos) {
            segmentBoundaries.pollFirst();
        }
    }

    /** Largest recorded segment-start boundary <= desired, or -1 if none. Caller holds lock. */
    private long snapToSegmentBoundary(long desired) {
        long best = -1;
        for (long b : segmentBoundaries) {
            if (b <= desired && b > best) {
                best = b;
            }
        }
        return best;
    }

    /** Append live bytes to the ring, advancing the live edge. Caller holds lock. */
    private void appendToRing(byte[] buffer, int offset, int length) {
        if (firstByteWallClockMs == 0) {
            firstByteWallClockMs = System.currentTimeMillis();
        }
        trackFrameBoundaries(buffer, offset, length, liveBytePos);
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

    /**
     * How many ms of audio behind the current read cursor are available to rewind into,
     * estimated via the measured byte-rate. 0 if not enough data yet.
     */
    public long getRewindableMs() {
        synchronized (lock) {
            long bytesPerSecond = measuredBytesPerSecond();
            if (bytesPerSecond <= 0) {
                return 0;
            }
            long rewindableBytes = readBytePos - oldestRetainedBytePos;
            return rewindableBytes * 1000 / bytesPerSecond;
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
     *
     * <p>The delegate is closed by the reader thread as it exits (never on the caller's
     * thread), because delegate.close() closes the SSL socket and does network I/O — doing
     * that on the main thread (release() is called from the player/main thread) would throw
     * NetworkOnMainThreadException.
     */
    public void release() {
        closed = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
        // delegate.close() happens in the reader thread's finally block.
    }
}
