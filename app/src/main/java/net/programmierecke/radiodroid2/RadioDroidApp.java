package net.programmierecke.radiodroid2;

import android.app.UiModeManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;

import net.programmierecke.radiodroid2.alarm.RadioAlarmManager;
import net.programmierecke.radiodroid2.history.TrackHistoryRepository;
import net.programmierecke.radiodroid2.players.mpd.MPDClient;
import net.programmierecke.radiodroid2.station.live.metadata.TrackMetadataSearcher;
import net.programmierecke.radiodroid2.proxy.ProxySettings;
import net.programmierecke.radiodroid2.recording.RecordingsManager;
import net.programmierecke.radiodroid2.utils.TvChannelManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RadioDroidApp extends MultiDexApplication {

    private HistoryManager historyManager;
    private FavouriteManager favouriteManager;
    private RecordingsManager recordingsManager;
    private FallbackStationsManager fallbackStationsManager;
    private RadioAlarmManager alarmManager;
    private TvChannelManager tvChannelManager;

    private TrackHistoryRepository trackHistoryRepository;

    private MPDClient mpdClient;

    private CastHandler castHandler;

    private TrackMetadataSearcher trackMetadataSearcher;

    private ConnectionPool connectionPool;
    private OkHttpClient httpClient;

    private Interceptor testsInterceptor;

    public class UserAgentInterceptor implements Interceptor {

        private final String userAgent;

        public UserAgentInterceptor(String userAgent) {
            this.userAgent = userAgent;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request originalRequest = chain.request();
            Request requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", userAgent)
                    .build();
            return chain.proceed(requestWithUserAgent);
        }
    }

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        GoogleProviderHelper.use(getBaseContext());

        connectionPool = new ConnectionPool();

        rebuildHttpClient();

        // Configure Glide with custom OkHttp client
        Glide.get(this).getRegistry().replace(GlideUrl.class, java.io.InputStream.class, new OkHttpUrlLoader.Factory(newHttpClientForGlide()));

        CountryCodeDictionary.getInstance().load(this);
        CountryFlagsLoader.getInstance();

        historyManager = new HistoryManager(this);
        favouriteManager = new FavouriteManager(this);
        fallbackStationsManager = new FallbackStationsManager(this);
        recordingsManager = new RecordingsManager(this);
        alarmManager = new RadioAlarmManager(this);

        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            tvChannelManager = new TvChannelManager(this);
            favouriteManager.addObserver(tvChannelManager);
        }

        trackHistoryRepository = new TrackHistoryRepository(this);

        mpdClient = new MPDClient(this);

        castHandler = new CastHandler();

        trackMetadataSearcher = new TrackMetadataSearcher(httpClient);

        recordingsManager.updateRecordingsList();
    }

    public void setTestsInterceptor(Interceptor testsInterceptor) {
        this.testsInterceptor = testsInterceptor;
    }

    public void rebuildHttpClient() {
        OkHttpClient.Builder builder = newHttpClient()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(new UserAgentInterceptor("RadioDroid2/" + BuildConfig.VERSION_NAME));

        httpClient = builder.build();
    }

    public FallbackStationsManager getFallbackStationsManager() {
        return fallbackStationsManager;
    }

    public HistoryManager getHistoryManager() {
        return historyManager;
    }

    public FavouriteManager getFavouriteManager() {
        return favouriteManager;
    }

    public RecordingsManager getRecordingsManager() {
        return recordingsManager;
    }

    public RadioAlarmManager getAlarmManager() {
        return alarmManager;
    }

    public TrackHistoryRepository getTrackHistoryRepository() {
        return trackHistoryRepository;
    }

    public MPDClient getMpdClient() {
        return mpdClient;
    }

    public CastHandler getCastHandler() {
        return castHandler;
    }

    public TrackMetadataSearcher getTrackMetadataSearcher() {
        return trackMetadataSearcher;
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public OkHttpClient.Builder newHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().connectionPool(connectionPool);

        if (testsInterceptor != null) {
            builder.addInterceptor(testsInterceptor);
        }

        if (!setCurrentOkHttpProxy(builder)) {
            Toast toast = Toast.makeText(this, getResources().getString(R.string.ignore_proxy_settings_invalid), Toast.LENGTH_SHORT);
            toast.show();
        }
        return Utils.enableTls12OnPreLollipop(builder);
    }

    public OkHttpClient.Builder newHttpClientWithoutProxy() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().connectionPool(connectionPool);

        if (testsInterceptor != null) {
            builder.addInterceptor(testsInterceptor);
        }

        return Utils.enableTls12OnPreLollipop(builder);
    }

    public boolean setCurrentOkHttpProxy(@NonNull OkHttpClient.Builder builder) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        ProxySettings proxySettings = ProxySettings.fromPreferences(sharedPref);
        if (proxySettings != null) {
            // proxy settings are not valid
            return Utils.setOkHttpProxy(builder, proxySettings);
        }
        return true;
    }

    private OkHttpClient newHttpClientForGlide() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .addInterceptor(new UserAgentInterceptor("RadioDroid2/" + BuildConfig.VERSION_NAME));

        if (testsInterceptor != null) {
            builder.addInterceptor(testsInterceptor);
        }

        setCurrentOkHttpProxy(builder);

        return builder.build();
    }
}
