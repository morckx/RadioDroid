package net.programmierecke.radiodroid2.players.exoplayer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.common.util.UnstableApi;

import okhttp3.OkHttpClient;

@UnstableApi
public class RadioDataSourceFactory implements DataSource.Factory {

    private final OkHttpClient httpClient;
    private final TransferListener transferListener;
    private final IcyDataSource.IcyDataSourceListener dataSourceListener;
    private final long retryTimeout;
    private final long retryDelay;
    private final int rewindBufferBytes;

    // The most recently created ring buffer, so the player can release() it on stop.
    // ProgressiveMediaSource creates one DataSource per media period, which for our live
    // single-item playback is effectively one instance per playRemote().
    private RingBufferDataSource lastRingBuffer;

    public RadioDataSourceFactory(@NonNull OkHttpClient httpClient,
                                  @NonNull TransferListener transferListener,
                                  @NonNull IcyDataSource.IcyDataSourceListener dataSourceListener,
                                  long retryTimeout,
                                  long retryDelay,
                                  int rewindBufferBytes) {
        this.httpClient = httpClient;
        this.transferListener = transferListener;
        this.dataSourceListener = dataSourceListener;
        this.retryTimeout = retryTimeout;
        this.retryDelay = retryDelay;
        this.rewindBufferBytes = rewindBufferBytes;
    }

    @Override
    public DataSource createDataSource() {
        IcyDataSource icyDataSource = new IcyDataSource(httpClient, transferListener, dataSourceListener);
        if (rewindBufferBytes > 0) {
            // Wrap the live source in a time-shift ring buffer so backward seeks can be
            // served from retained bytes (rewind feature).
            RingBufferDataSource ringBuffer = new RingBufferDataSource(icyDataSource, rewindBufferBytes);
            lastRingBuffer = ringBuffer;
            return ringBuffer;
        }
        return icyDataSource;
    }

    /** The active ring buffer, if any, so the player can release() it. */
    @Nullable
    public RingBufferDataSource getRingBuffer() {
        return lastRingBuffer;
    }
}
