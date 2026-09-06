package net.programmierecke.radiodroid2.players.exoplayer;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Map;

/**
 * PROTOTYPE #2 (rewind feature): a thin instrumentation wrapper around {@link IcyDataSource}
 * that answers the single riskiest question of the rolling-buffer design:
 *
 *   Does media3's ProgressiveMediaSource ever call open() again with a REWOUND
 *   dataSpec.position when we ask the player to seekTo() a backward position?
 *
 * It does not implement any buffering yet. It only logs, so we can observe from
 * logcat whether the seek reaches the DataSource layer at all. If open() is never
 * re-invoked with an earlier position, the blocker is the Extractor's SeekMap
 * (unseekable for live MP3), not the DataSource — and a ring buffer alone won't help.
 *
 * Wraps rather than extends so the real IcyDataSource logic (ICY metadata stripping,
 * reconnect, listeners) is untouched.
 */
@UnstableApi
public class RewindProbeDataSource implements DataSource {

    private static final String TAG = "RewindProbe";

    private final IcyDataSource delegate;

    private long lastOpenPosition = -1;
    private long totalBytesReadSinceOpen = 0;

    public RewindProbeDataSource(IcyDataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        Log.i(TAG, "open() position=" + dataSpec.position
                + " length=" + dataSpec.length
                + " (previous open position was " + lastOpenPosition + ")");
        lastOpenPosition = dataSpec.position;
        totalBytesReadSinceOpen = 0;
        return delegate.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        int n = delegate.read(buffer, offset, readLength);
        if (n > 0) {
            totalBytesReadSinceOpen += n;
        }
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return delegate.getUri();
    }

    @Override
    public Map<String, java.util.List<String>> getResponseHeaders() {
        return delegate.getResponseHeaders();
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        delegate.addTransferListener(transferListener);
    }

    @Override
    public void close() throws IOException {
        Log.i(TAG, "close() after reading " + totalBytesReadSinceOpen
                + " bytes from open position " + lastOpenPosition);
        delegate.close();
    }
}
