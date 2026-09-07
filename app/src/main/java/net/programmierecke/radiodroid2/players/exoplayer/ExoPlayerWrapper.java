package net.programmierecke.radiodroid2.players.exoplayer;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Metadata;
import androidx.media3.extractor.metadata.icy.IcyHeaders;
import androidx.media3.extractor.metadata.icy.IcyInfo;
import androidx.media3.extractor.metadata.id3.Id3Frame;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.common.util.UnstableApi;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.players.PlayState;
import net.programmierecke.radiodroid2.players.PlayerWrapper;
import net.programmierecke.radiodroid2.recording.RecordableListener;
import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

@UnstableApi
public class ExoPlayerWrapper implements PlayerWrapper, IcyDataSource.IcyDataSourceListener, Player.Listener {

    final private String TAG = "ExoPlayerWrapper";

    // Rewind feature: this is a single 15s live <-> -15s toggle, so we only need to retain a
    // little more than one 15s step. The extra headroom covers ICY segment-boundary snapping
    // and byte-rate estimation variance so a full 15s is always available. Keeping this small
    // (vs. a long time-shift window) keeps the memory and any battery cost negligible.
    // The ExoPlayer LoadControl back-buffer keeps decoded samples so the seek is accepted;
    // the RingBufferDataSource retains the corresponding raw stream bytes to serve the rewind.
    private static final int REWIND_BACK_BUFFER_MS = 20_000;

    // Raw byte capacity of the time-shift ring buffer. Sized for REWIND_BACK_BUFFER_MS at a
    // generous ~192 kbps (24 KB/s) so typical talk/news streams fit comfortably (~470 KB).
    private static final int REWIND_RING_BUFFER_BYTES = (REWIND_BACK_BUFFER_MS / 1000) * 24 * 1024;

    private ExoPlayer player;
    private PlayListener stateListener;

    private String streamUrl;

    //private DefaultBandwidthMeter bandwidthMeter;

    private RecordableListener recordableListener;

    private long totalTransferredBytes;
    private long currentPlaybackTransferredBytes;

    private boolean isHls;
    private boolean isPlayingFlag;

    private Handler playerThreadHandler;

    private Context context;
    private MediaSource audioSource;
    private RadioDataSourceFactory radioDataSourceFactory;
    private boolean rewindEnabled;

    private Runnable fullStopTask;

    private final BroadcastReceiver networkChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (fullStopTask != null && player != null && audioSource != null && Utils.hasAnyConnection(context)) {
                Log.i(TAG, "Regained connection. Resuming playback.");

                cancelStopTask();

                player.setMediaSource(audioSource);
                player.prepare();
                player.setPlayWhenReady(true);
            }
        }
    };

    @Override
    public void playRemote(@NonNull OkHttpClient httpClient, @NonNull String streamUrl, @NonNull Context context, boolean isAlarm) {
        // I don't know why, but it is still possible that streamUrl is null,
        // I still get exceptions from this from google
        if (!streamUrl.equals(this.streamUrl)) {
            currentPlaybackTransferredBytes = 0;
        }

        this.context = context;
        this.streamUrl = streamUrl;

        cancelStopTask();

        stateListener.onStateChanged(PlayState.PrePlaying);

        // Rewind feature: on by default, opt-out via settings. When disabled, no back-buffer
        // and no ring buffer are kept, so there is zero extra memory/CPU.
        rewindEnabled = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .getBoolean("enable_rewind", true);

        if (player != null) {
            player.stop();
        }

        if (player == null) {
            ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(context);
            if (rewindEnabled) {
                // Ask ExoPlayer to retain already-played audio so backward seeks are accepted,
                // retained from the last keyframe so they land on a decodable position. The
                // RingBufferDataSource holds the corresponding raw bytes for the actual rewind.
                LoadControl loadControl = new DefaultLoadControl.Builder()
                        .setBackBuffer(REWIND_BACK_BUFFER_MS, /* retainBackBufferFromKeyframe= */ true)
                        .build();
                playerBuilder.setLoadControl(loadControl);
            }
            player = playerBuilder.build();
            player.setAudioAttributes(new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(isAlarm ? C.USAGE_ALARM : C.USAGE_MEDIA).build(), false);

            player.addListener(this);
            player.addAnalyticsListener(new AnalyticEventListener());
        }

        if (playerThreadHandler == null) {
            playerThreadHandler = new Handler(Looper.getMainLooper());
        }

        isHls = Utils.urlIndicatesHlsStream(streamUrl);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        final int retryTimeout = prefs.getInt("settings_retry_timeout", 10);
        final int retryDelay = prefs.getInt("settings_retry_delay", 100);

        // Enable the time-shift ring buffer only when rewind is enabled and for progressive
        // (ICY) streams; HLS has its own live window and a different seek path.
        final int rewindBufferBytes = (rewindEnabled && !isHls) ? REWIND_RING_BUFFER_BYTES : 0;
        radioDataSourceFactory = new RadioDataSourceFactory(httpClient, new DefaultBandwidthMeter.Builder(context).build(), this, retryTimeout, retryDelay, rewindBufferBytes);
        DataSource.Factory dataSourceFactory = radioDataSourceFactory;
        // Produces Extractor instances for parsing the media data.
        if (!isHls) {
            audioSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(new CustomLoadErrorHandlingPolicy())
                    .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)));
            player.setMediaSource(audioSource);
            player.prepare();
        } else {
            audioSource = new HlsMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(new CustomLoadErrorHandlingPolicy())
                    .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)));
            player.setMediaSource(audioSource);
            player.prepare();
        }

        player.setPlayWhenReady(true);

        context.registerReceiver(networkChangedReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        // State changed will be called when audio session id is available.
    }

    // Rewind feature: how many ms of audio are currently retained and rewindable, or 0 if
    // there is no ring buffer (HLS / disabled) or not enough buffered yet.
    public long getRewindableMs() {
        RingBufferDataSource ringBuffer =
                (radioDataSourceFactory != null) ? radioDataSourceFactory.getRingBuffer() : null;
        if (ringBuffer == null) {
            return 0;
        }
        return ringBuffer.getRewindableMs();
    }

    // Rewind feature: fully tear down the time-shift ring buffer (background reader +
    // live connection). close() is reused by media3 for seeks, so teardown is explicit.
    private void releaseRingBuffer() {
        if (radioDataSourceFactory != null) {
            RingBufferDataSource ringBuffer = radioDataSourceFactory.getRingBuffer();
            if (ringBuffer != null) {
                ringBuffer.release();
            }
        }
    }

    @Override
    public void pause() {
        Log.i(TAG, "Pause. Stopping exoplayer.");

        cancelStopTask();

        if (player != null) {
            context.unregisterReceiver(networkChangedReceiver);
            player.stop();
            player.release();
            player = null;
            releaseRingBuffer();
        }
    }

    @Override
    public void stop() {
        Log.i(TAG, "Stopping exoplayer.");

        cancelStopTask();

        if (player != null) {
            context.unregisterReceiver(networkChangedReceiver);
            player.stop();
            player.release();
            player = null;
            releaseRingBuffer();
        }

        stopRecording();
    }

    @Override
    public boolean isPlaying() {
        return player != null && isPlayingFlag;
    }

    @Override
    public long getBufferedMs() {
        if (player != null) {
            return (int) (player.getBufferedPosition() - player.getCurrentPosition());
        }

        return 0;
    }

    // Rewind feature: jump back by the given amount within the retained time-shift buffer.
    // Returns how many ms we actually moved back (0 if nothing was available).
    @Override
    public long seekBackward(long ms) {
        if (player == null) {
            return 0;
        }
        RingBufferDataSource ringBuffer =
                (radioDataSourceFactory != null) ? radioDataSourceFactory.getRingBuffer() : null;
        if (ringBuffer == null) {
            Log.w(TAG, "seekBackward: no ring buffer (HLS or disabled), cannot rewind");
            return 0;
        }

        // The live stream is unseekable (SeekMap), so we cannot use a positioned seekTo.
        // Instead: tell the ring buffer to move its read cursor back, then force ExoPlayer
        // to restart reading (seekTo(0) triggers a re-open, which our open() honours via the
        // pending rewind position).
        long movedMs = ringBuffer.rewindBy(ms);
        if (movedMs > 0) {
            player.seekTo(0);
            player.setPlayWhenReady(true);
        }
        Log.d(TAG, "seekBackward: requested=" + ms + " movedMs=" + movedMs);
        return movedMs;
    }

    // Rewind feature: jump forward to the live edge, undoing a rewind.
    public long seekToLive() {
        if (player == null) {
            return 0;
        }
        RingBufferDataSource ringBuffer =
                (radioDataSourceFactory != null) ? radioDataSourceFactory.getRingBuffer() : null;
        if (ringBuffer == null) {
            return 0;
        }
        long movedMs = ringBuffer.seekToLive();
        if (movedMs > 0) {
            player.seekTo(0);
            player.setPlayWhenReady(true);
        }
        Log.d(TAG, "seekToLive: movedMs=" + movedMs);
        return movedMs;
    }

    // Rewind feature: whether playback is currently behind the live edge (rewound).
    public boolean isBehindLive() {
        RingBufferDataSource ringBuffer =
                (radioDataSourceFactory != null) ? radioDataSourceFactory.getRingBuffer() : null;
        return ringBuffer != null && ringBuffer.isBehindLive();
    }

    @Override
    public int getAudioSessionId() {
        if (player != null) {
            return player.getAudioSessionId();
        }
        return 0;
    }

    @Override
    public long getTotalTransferredBytes() {
        return totalTransferredBytes;
    }

    @Override
    public long getCurrentPlaybackTransferredBytes() {
        return currentPlaybackTransferredBytes;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public void setVolume(float newVolume) {
        if (player != null) {
            player.setVolume(newVolume);
        }
    }

    @Override
    public void setStateListener(PlayListener listener) {
        stateListener = listener;
    }

    @Override
    public void onDataSourceConnected() {

    }

    @Override
    public void onDataSourceConnectionLost() {

    }

    @Override
    public void onMetadata(@NonNull Metadata metadata) {
        if (BuildConfig.DEBUG) Log.d(TAG, "META: " + metadata);
        final int length = metadata.length();
        if (length > 0) {
            for (int i = 0; i < length; i++) {
                final Metadata.Entry entry = metadata.get(i);
                if (entry == null) {
                    continue;
                }
                if (entry instanceof IcyInfo icyInfo) {
                    Log.d(TAG, "IcyInfo: " + icyInfo);
                    if (icyInfo.title != null) {
                        Map<String, String> rawMetadata = new HashMap<>() {{
                            put("StreamTitle", icyInfo.title);
                        }};
                        StreamLiveInfo streamLiveInfo = new StreamLiveInfo(rawMetadata);
                        onDataSourceStreamLiveInfo(streamLiveInfo);
                    }
                } else if (entry instanceof IcyHeaders icyHeaders) {
                    Log.d(TAG, "IcyHeaders: " + icyHeaders);
                    onDataSourceShoutcastInfo(new ShoutcastInfo(icyHeaders));
                } else if (entry instanceof Id3Frame id3Frame) {
                    Log.d(TAG, "id3 metadata: " + id3Frame);
                }
            }
        }
    }

    @Override
    public void onDataSourceConnectionLostIrrecoverably() {
        Log.i(TAG, "Connection lost irrecoverably.");
    }

    void resumeWhenNetworkConnected() {
        playerThreadHandler.post(() -> {
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
            int resumeWithin = sharedPref.getInt("settings_resume_within", 60);
            if (resumeWithin > 0) {
                Log.d(TAG, "Trying to resume playback within " + resumeWithin + "s.");

                // We want user to be able to paused during connection loss.
                // TODO: Find a way to notify user that even if current state is Playing
                //       we are actually trying to reconnect.
                //stateListener.onStateChanged(PlayState.Paused);

                cancelStopTask();

                fullStopTask = () -> {
                    stop();
                    stateListener.onPlayerError(R.string.giving_up_resume);

                    ExoPlayerWrapper.this.fullStopTask = null;
                };
                playerThreadHandler.postDelayed(fullStopTask, resumeWithin * 1000L);

                stateListener.onPlayerWarning(R.string.warning_no_network_trying_resume);
            } else {
                stop();

                stateListener.onPlayerError(R.string.error_stream_reconnect_timeout);
            }
        });
    }

    @Override
    public void onDataSourceShoutcastInfo(@Nullable ShoutcastInfo shoutcastInfo) {
        stateListener.onDataSourceShoutcastInfo(shoutcastInfo, false);

        // Rewind feature: give the ring buffer the ICY metadata interval so it can align
        // rewind targets to audio-segment boundaries (avoids clicks/garbage after rewind).
        if (radioDataSourceFactory != null) {
            RingBufferDataSource ringBuffer = radioDataSourceFactory.getRingBuffer();
            if (ringBuffer != null && shoutcastInfo != null) {
                ringBuffer.setMetaint(shoutcastInfo.metadataOffset);
            }
        }
    }

    @Override
    public void onDataSourceStreamLiveInfo(StreamLiveInfo streamLiveInfo) {
        stateListener.onDataSourceStreamLiveInfo(streamLiveInfo);
    }

    @Override
    public void onDataSourceBytesRead(byte[] buffer, int offset, int length) {
        totalTransferredBytes += length;
        currentPlaybackTransferredBytes += length;

        if (recordableListener != null) {
            // Create a copy of the buffer to avoid race conditions with buffer reuse
            byte[] recordingBuffer = new byte[length];
            System.arraycopy(buffer, offset, recordingBuffer, 0, length);
            recordableListener.onBytesAvailable(recordingBuffer, 0, length);
        }
    }

    @Override
    public boolean canRecord() {
        return player != null;
    }

    @Override
    public void startRecording(@NonNull RecordableListener recordableListener) {
        this.recordableListener = recordableListener;
    }

    @Override
    public void stopRecording() {
        if (recordableListener != null) {
            recordableListener.onRecordingEnded();
            recordableListener = null;
        }
    }

    @Override
    public boolean isRecording() {
        return recordableListener != null;
    }

    @Override
    public Map<String, String> getRecordNameFormattingArgs() {
        return null;
    }

    @Override
    public String getExtension() {
        return isHls ? "ts" : "mp3";
    }

    private void cancelStopTask() {
        if (fullStopTask != null) {
            playerThreadHandler.removeCallbacks(fullStopTask);
            fullStopTask = null;
        }
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        // Do nothing
    }

    @Override
    public void onPlayerErrorChanged(PlaybackException error) {
        Log.d(TAG, "Player error: ", error);
        // Stop playing since it is either irrecoverable error in the player or our data source failed to reconnect.
        if (fullStopTask != null) {
            stop();
            stateListener.onPlayerError(R.string.error_play_stream);
        }
    }

    @Override
    public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
        // Do nothing
    }

    final class CustomLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
        final int MIN_RETRY_DELAY_MS = 10;
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        // We need to read the retry delay here on each error again because the user might change
        // this value between retries and experiment with different vales to get the best result for
        // the specific situation. We also need to make sure that a sensible minimum value is chosen.
        int getSanitizedRetryDelaySettingsMs() {
            return Math.max(sharedPrefs.getInt("settings_retry_delay", 100), MIN_RETRY_DELAY_MS);
        }

        @Override
        public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {

            int retryDelay = getSanitizedRetryDelaySettingsMs();
            IOException exception = loadErrorInfo.exception;

            if (exception instanceof HttpDataSource.InvalidContentTypeException) {
                stateListener.onPlayerError(R.string.error_play_stream);
                return C.TIME_UNSET; // Immediately surface error if we cannot play content type
            }

            if (!Utils.hasAnyConnection(context)) {
                int resumeWithinS = sharedPrefs.getInt("settings_resume_within", 60);
                if (resumeWithinS > 0) {
                    resumeWhenNetworkConnected();
                    retryDelay = 1000 * resumeWithinS + retryDelay;
                }
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Providing retry delay of " + retryDelay + "ms " +
                        "error count: " + loadErrorInfo.errorCount + ", " +
                        "exception " + exception.getClass() + ", " +
                        "message: " + exception.getMessage());
            }
            return retryDelay;
        }

        @Override
        public int getMinimumLoadableRetryCount(int dataType) {
            return sharedPrefs.getInt("settings_retry_timeout", 10) * 1000 / getSanitizedRetryDelaySettingsMs() + 1;
        }
    }

    private class AnalyticEventListener implements AnalyticsListener {
        @Override
        public void onPlayerStateChanged(EventTime eventTime, boolean playWhenReady, int playbackState) {
            isPlayingFlag = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING;

            switch (playbackState) {
                case Player.STATE_READY:
                    cancelStopTask();
                    stateListener.onStateChanged(PlayState.Playing);
                    break;
                case Player.STATE_BUFFERING:
                    stateListener.onStateChanged(PlayState.PrePlaying);
                    break;
            }

        }

        @Override
        public void onTimelineChanged(@NonNull EventTime eventTime, int reason) {

        }

        @Override
        public void onPlaybackParametersChanged(@NonNull EventTime eventTime, @NonNull PlaybackParameters playbackParameters) {

        }

        @Override
        public void onRepeatModeChanged(@NonNull EventTime eventTime, int repeatMode) {

        }

        @Override
        public void onShuffleModeChanged(@NonNull EventTime eventTime, boolean shuffleModeEnabled) {

        }

        @Override
        public void onBandwidthEstimate(@NonNull EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {

        }

        @Override
        public void onSurfaceSizeChanged(@NonNull EventTime eventTime, int width, int height) {

        }

        @Override
        public void onMetadata(@NonNull EventTime eventTime, @NonNull Metadata metadata) {

        }

        @Override
        public void onAudioAttributesChanged(@NonNull EventTime eventTime, @NonNull AudioAttributes audioAttributes) {

        }

        @Override
        public void onVolumeChanged(@NonNull EventTime eventTime, float volume) {

        }

        @Override
        public void onDroppedVideoFrames(@NonNull EventTime eventTime, int droppedFrames, long elapsedMs) {

        }

        @Override
        public void onDrmKeysLoaded(@NonNull EventTime eventTime) {

        }

        @Override
        public void onDrmSessionManagerError(@NonNull EventTime eventTime, @NonNull Exception error) {

        }

        @Override
        public void onDrmKeysRestored(@NonNull EventTime eventTime) {

        }

        @Override
        public void onDrmKeysRemoved(@NonNull EventTime eventTime) {

        }

        @Override
        public void onDrmSessionReleased(@NonNull EventTime eventTime) {

        }
    }
}
