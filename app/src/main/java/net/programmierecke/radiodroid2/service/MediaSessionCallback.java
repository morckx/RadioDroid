package net.programmierecke.radiodroid2.service;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.RequiresApi;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.programmierecke.radiodroid2.IPlayerService;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.SearchResultsManager;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.utils.GetRealLinkAndPlayTask;

public class MediaSessionCallback extends MediaSessionCompat.Callback {
    public static final String BROADCAST_PLAY_STATION_BY_ID = "PLAY_STATION_BY_ID";
    public static final String EXTRA_STATION_ID = "STATION_ID";
    public static final String ACTION_PLAY_STATION_BY_UUID = "PLAY_STATION_BY_UUID";
    public static final String EXTRA_STATION_UUID = "STATION_UUID";
    private static final String TAG = "MediaSessionCallback";

    private Context context;
    private IPlayerService playerService;

    public MediaSessionCallback(Context context, IPlayerService playerService) {
        this.context = context;
        this.playerService = playerService;
    }

    @Override
    public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
        final KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);

        if (event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isLongPress()) {
                try {
                    if (playerService.isPlaying()) {
                        playerService.Pause(PauseReason.USER);
                    } else {
                        playerService.Resume();
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
            return true;
        } else {
            return super.onMediaButtonEvent(mediaButtonEvent);
        }
    }

    @Override
    public void onPause() {
        try {
            playerService.Pause(PauseReason.USER);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPlay() {
        try {
            playerService.Resume();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSkipToNext() {
        try {
            playerService.SkipToNext();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSkipToPrevious() {
        try {
            playerService.SkipToPrevious();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onStop() {
        try {
            playerService.Stop();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPlayFromMediaId(String mediaId, Bundle extras) {
        final String stationId = RadioDroidBrowser.stationIdFromMediaId(mediaId);

        if (!stationId.isEmpty()) {
            Intent intent = new Intent(BROADCAST_PLAY_STATION_BY_ID);
            intent.putExtra(EXTRA_STATION_ID, stationId);

            LocalBroadcastManager bm = LocalBroadcastManager.getInstance(context);
            bm.sendBroadcast(intent);
        }
    }

    @Override
    public void onPlayFromSearch(String query, Bundle extras) {
        // remove voice search residues like " with radiodroid"
        query = query.replaceAll("(?i) \\w+ radio\\s*droid.*", "");
        SearchResultsManager searchResultsManager = ((RadioDroidApp) context.getApplicationContext()).getSearchResultsManager();
        try {
            playerService.setPlaybackState(PlaybackStateCompat.STATE_CONNECTING);
        } catch (RemoteException e) {}
        new SearchResultsManager.AsyncStationSearchTask(searchResultsManager, query, bestMatchStation -> {
            try {
                if (bestMatchStation != null) {
                    playerService.SetStation(bestMatchStation);
                    playerService.Play(false);
                } else {
                    playerService.setPlaybackState(PlaybackStateCompat.STATE_ERROR);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }).execute();
    }
}
