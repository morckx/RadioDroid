package net.programmierecke.radiodroid2.service;

import android.content.ContentResolver;
import static androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE;
import static androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE;
import static androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM;
import static androidx.media.utils.MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.request.target.CustomTarget;
import net.programmierecke.radiodroid2.utils.ImageLoader;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.RadioDroidApp;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


import static net.programmierecke.radiodroid2.Utils.resourceToUri;


public class RadioDroidBrowser {
    private static final String TAG = "RadioDroidBrowser";
    private static final String MEDIA_ID_ROOT = "__ROOT__";
    private static final String MEDIA_ID_MUSICS_FAVORITE = "__FAVORITE__";
    private static final String MEDIA_ID_MUSICS_HISTORY = "__HISTORY__";
    private static final String MEDIA_ID_MUSICS_TOP = "__TOP__";
    private static final String MEDIA_ID_MUSICS_TOP_TAGS = "__TOP_TAGS__";

    private static final char LEAF_SEPARATOR = '|';

    private static final int IMAGE_LOAD_TIMEOUT_MS = 2000;

    private final RadioDroidApp radioDroidApp;

    private final Map<String, DataRadioStation> stationIdToStation = new HashMap<>();

    private static class RetrieveStationsIconAndSendResult extends AsyncTask<Void, Void, Void> {
        private final MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>> result;
        private final List<DataRadioStation> stations;
        private final WeakReference<Context> contextRef;

        private final Map<String, Bitmap> stationIdToIcon = new HashMap<>();
        private CountDownLatch countDownLatch;
        private final Resources resources;
        // Glide custom targets for image loading
        List<ImageLoader.BitmapTarget> imageLoadTargets = new ArrayList<>();

        RetrieveStationsIconAndSendResult(MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>> result, List<DataRadioStation> stations, Context context) {
            this.result = result;
            this.stations = stations;
            this.contextRef = new WeakReference<>(context);
            resources = context.getApplicationContext().getResources();
        }

        @Override
        protected void onPreExecute() {
            countDownLatch = new CountDownLatch(stations.size());

            for (final DataRadioStation station : stations) {
                Context context = contextRef.get();
                if (context == null) {
                    break;
                }

                ImageLoader.BitmapTarget imageLoadTarget = new ImageLoader.BitmapTarget() {
                    @Override
                    public void onBitmapLoaded(Bitmap bitmap) {
                        stationIdToIcon.put(station.StationUuid, bitmap);
                        countDownLatch.countDown();
                    }

                    @Override
                    public void onBitmapFailed(Drawable errorDrawable) {
                        if (errorDrawable instanceof BitmapDrawable) {
                            onBitmapLoaded(((BitmapDrawable) errorDrawable).getBitmap());
                        } else {
                            countDownLatch.countDown();
                        }
                    }

                    @Override
                    public void onPrepareLoad(Drawable placeHolderDrawable) {
                        // No action needed
                    }
                };
                imageLoadTargets.add(imageLoadTarget);
                
                String iconUrl = station.hasIcon() ? station.IconUrl : resourceToUri(resources, R.drawable.ic_launcher).toString();
                ImageLoader.loadStationIconForBrowser(context, iconUrl, 128, Utils.useCircularIcons(context), imageLoadTarget);
            }

            super.onPreExecute();
        }

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                countDownLatch.await(IMAGE_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            return null;
        }


        @Override
        protected void onPostExecute(Void aVoid) {
            Context context = contextRef.get();
            if (context != null) {
                // Note: Glide BitmapTarget doesn't require explicit cancellation like Picasso
                // The request will be automatically cancelled when the target is no longer referenced
            }

            List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

            for (DataRadioStation station : stations) {

                Bitmap stationIcon = stationIdToIcon.get(station.StationUuid);

                if (stationIcon == null)
                    stationIcon = BitmapFactory.decodeResource(Resources.getSystem(), R.drawable.ic_launcher);
                Bundle extras = new Bundle();
                extras.putParcelable(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, stationIcon);
                extras.putParcelable(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, stationIcon);
                MediaDescriptionCompat.Builder mediaItem = new MediaDescriptionCompat.Builder()
                        .setMediaId(MEDIA_ID_MUSICS_HISTORY + LEAF_SEPARATOR + station.StationUuid)
                        .setTitle(station.Name)
                        .setDescription(station.Country + " " + station.Country + " " + station.TagsAll)
                        .setExtras(extras);

                if (station.IconUrl != null && !station.IconUrl.isEmpty()) {
                    String iconUrl = station.IconUrl;
                    if (iconUrl.startsWith("http:")) {
                        iconUrl = iconUrl.replace("http:", "https:");
                    }
                    mediaItem.setIconUri(Uri.parse(iconUrl));
                } else {
                    mediaItem.setIconUri(resourceToUri(resources, R.drawable.ic_photo_24dp));
                }

                mediaItems.add(new MediaBrowserCompat.MediaItem(mediaItem.build(),
                        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            }

            result.sendResult(mediaItems);

            super.onPostExecute(aVoid);
        }
    }

    public RadioDroidBrowser(RadioDroidApp radioDroidApp) {
        this.radioDroidApp = radioDroidApp;
    }

   @Nullable
    public MediaBrowserServiceCompat.BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
       SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(radioDroidApp.getApplicationContext().getApplicationContext());
       Bundle extras = new Bundle();
       extras.putInt(DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM);
       if (sharedPref.getBoolean("load_icons", false) && sharedPref.getBoolean("icons_only_favorites_style", false)) {
           Log.d(TAG, "Setting grid style for playables");
           extras.putInt(
                   DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                   DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM);
       } else {
           Log.d(TAG, "Setting list style for playables");
           extras.putInt(
                   DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                   DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM);
       }
//       extras.putBoolean(CONTENT_STYLE_SUPPORTED, true);
       return new MediaBrowserServiceCompat.BrowserRoot(MEDIA_ID_ROOT, extras);
    }

    public void onLoadChildren(@NonNull String parentId, @NonNull MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>> result) {
        Resources resources = radioDroidApp.getResources();
        if (MEDIA_ID_ROOT.equals(parentId)) {
            result.sendResult(createBrowsableMediaItemsForRoot(resources));
            return;
        }

        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();

        List<DataRadioStation> stations = null;

        switch (parentId) {
            case MEDIA_ID_MUSICS_FAVORITE: {
                stations = radioDroidApp.getFavouriteManager().getList();
                break;
            }
            case MEDIA_ID_MUSICS_HISTORY: {
                stations = radioDroidApp.getHistoryManager().getList();
                break;
            }
            case MEDIA_ID_MUSICS_TOP: {

                break;
            }
        }

        if (stations != null && !stations.isEmpty()) {
            stationIdToStation.clear();
            for (DataRadioStation station : stations) {
                stationIdToStation.put(station.StationUuid, station);
            }
            result.detach();
            new RetrieveStationsIconAndSendResult(result, stations, radioDroidApp).execute();
        } else {
            result.sendResult(mediaItems);
        }

    }

    @Nullable
    public DataRadioStation getStationById(@NonNull String stationId) {
        return stationIdToStation.get(stationId);
    }

    private List<MediaBrowserCompat.MediaItem> createBrowsableMediaItemsForRoot(Resources resources) {
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
        mediaItems.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                .setMediaId(MEDIA_ID_MUSICS_FAVORITE)
                .setTitle(resources.getString(R.string.nav_item_starred))
                .setIconUri(resourceToUri(resources, R.drawable.ic_star_white_24))
                .build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));

        mediaItems.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                .setMediaId(MEDIA_ID_MUSICS_HISTORY)
                .setTitle(resources.getString(R.string.nav_item_history))
                .setIconUri(resourceToUri(resources, R.drawable.ic_star_white_24))
                .build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));

/*        mediaItems.add(new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder()
                .setMediaId(MEDIA_ID_MUSICS_TOP)
                .setTitle(resources.getString(R.string.action_top_click))
                .setIconUri(resourceToUri(resources, R.drawable.ic_restore_black_24dp))
                .build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));*/
        return mediaItems;
    }

    public static String stationIdFromMediaId(final String mediaId) {
        if (mediaId == null) {
            return "";
        }

        final int separatorIdx = mediaId.indexOf(LEAF_SEPARATOR);

        if (separatorIdx <= 0) {
            return mediaId;
        }

        return mediaId.substring(separatorIdx + 1);
    }
}
