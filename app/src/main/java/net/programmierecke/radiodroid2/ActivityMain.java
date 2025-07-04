package net.programmierecke.radiodroid2;

import static net.programmierecke.radiodroid2.service.MediaSessionCallback.EXTRA_STATION_UUID;

import android.app.TimePickerDialog;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.bytehamster.lib.preferencesearch.SearchPreferenceResult;
import com.bytehamster.lib.preferencesearch.SearchPreferenceResultListener;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;
import com.mikepenz.iconics.Iconics;
import com.rustamg.filedialogs.FileDialog;
import com.rustamg.filedialogs.OpenFileDialog;
import com.rustamg.filedialogs.SaveFileDialog;

import net.programmierecke.radiodroid2.alarm.FragmentAlarm;
import net.programmierecke.radiodroid2.alarm.TimePickerFragment;
import net.programmierecke.radiodroid2.cast.CastAwareActivity;
import net.programmierecke.radiodroid2.interfaces.IFragmentSearchable;
import net.programmierecke.radiodroid2.players.PlayState;
import net.programmierecke.radiodroid2.players.PlayStationTask;
import net.programmierecke.radiodroid2.players.mpd.MPDClient;
import net.programmierecke.radiodroid2.players.mpd.MPDServersRepository;
import net.programmierecke.radiodroid2.players.selector.PlayerType;
import net.programmierecke.radiodroid2.service.MediaSessionCallback;
import net.programmierecke.radiodroid2.service.PlayerService;
import net.programmierecke.radiodroid2.service.PlayerServiceUtil;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import net.programmierecke.radiodroid2.station.StationsFilter;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Date;

import okhttp3.OkHttpClient;

public class ActivityMain extends AppCompatActivity implements SearchView.OnQueryTextListener,
        NavigationView.OnNavigationItemSelectedListener,
        BottomNavigationView.OnNavigationItemSelectedListener,
        FileDialog.OnFileSelectedListener,
        TimePickerDialog.OnTimeSetListener,
        SearchPreferenceResultListener,
        CastAwareActivity {

    public static final String EXTRA_SEARCH_TAG = "search_tag";

    public static final int LAUNCH_EQUALIZER_REQUEST = 1;

    public final static int MAX_DYNAMIC_LAUNCHER_SHORTCUTS = 4;

    public static final int FRAGMENT_FROM_BACKSTACK = 777;

    public static final String ACTION_SHOW_LOADING = "net.programmierecke.radiodroid2.show_loading";
    public static final String ACTION_HIDE_LOADING = "net.programmierecke.radiodroid2.hide_loading";
    public static final int PERM_REQ_STORAGE_FAV_SAVE = 1;
    public static final int PERM_REQ_STORAGE_FAV_LOAD = 2;
    private static final String TAG = "RadioDroid";
    // Request code for creating a PDF document.
    private static final int ACTION_SAVE_FILE = 1;
    private static final int ACTION_LOAD_FILE = 2;
    private final String TAG_SEARCH_URL = "json/stations/bytagexact";
    private final String SAVE_LAST_MENU_ITEM = "LAST_MENU_ITEM";
    DrawerLayout mDrawerLayout;
    NavigationView mNavigationView;
    BottomNavigationView mBottomNavigationView;
    FragmentManager mFragmentManager;
    BroadcastReceiver broadcastReceiver;
    MenuItem menuItemSearch;
    MenuItem menuItemDelete;
    MenuItem menuItemSleepTimer;
    MenuItem menuItemSave;
    MenuItem menuItemLoad;
    MenuItem menuItemIconsView;
    MenuItem menuItemListView;
    MenuItem menuItemAddAlarm;
    MenuItem menuItemMpd;
    private SearchView mSearchView;
    private AppBarLayout appBarLayout;
    private TabLayout tabsView;
    private BottomSheetBehavior playerBottomSheet;
    private FragmentPlayerSmall smallPlayerFragment;
    private FragmentPlayerFull fullPlayerFragment;
    private SharedPreferences sharedPref;

    private int selectedMenuItem;

    private boolean instanceStateWasSaved;

    private Date lastExitTry;

    private AlertDialog meteredConnectionAlertDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Iconics.init(this);

        super.onCreate(savedInstanceState);

        if (sharedPref == null) {
            PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
            sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        }
        setTheme(Utils.getThemeResId(this));
        setContentView(R.layout.layout_main);

        Log.d(TAG, "FilesDir: " + getFilesDir().getAbsolutePath());
        Log.d(TAG, "CacheDir: " + getCacheDir().getAbsolutePath());
        try {
            File dir = new File(getFilesDir().getAbsolutePath());
            if (dir.isDirectory()) {

                String[] children = dir.list();
                for (String aChildren : children) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "delete file:" + aChildren);
                    }
                    try {
                        new File(dir, aChildren).delete();
                    } catch (Exception e) {
                    }
                }
            }
        } catch (Exception e) {
        }

        final Toolbar myToolbar = findViewById(R.id.my_awesome_toolbar);
        setSupportActionBar(myToolbar);

        PlayerServiceUtil.startService(getApplicationContext());

        selectedMenuItem = sharedPref.getInt("last_selectedMenuItem", -1);
        instanceStateWasSaved = savedInstanceState != null;
        mFragmentManager = getSupportFragmentManager();

        appBarLayout = findViewById(R.id.app_bar_layout);
        tabsView = findViewById(R.id.tabs);
        mDrawerLayout = findViewById(R.id.drawerLayout);
        mNavigationView = findViewById(R.id.my_navigation_view);
        mBottomNavigationView = findViewById(R.id.bottom_navigation);

        if (useBottomNavigation()) {
            mBottomNavigationView.setOnNavigationItemSelectedListener(this);
            mNavigationView.setVisibility(View.GONE);
            mNavigationView.getLayoutParams().width = 0;
        } else {
            mNavigationView.setNavigationItemSelectedListener(this);
            mBottomNavigationView.setVisibility(View.GONE);

            ActionBarDrawerToggle mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, R.string.app_name, R.string.app_name);
            mDrawerLayout.addDrawerListener(mDrawerToggle);

            // Add custom drawer listener for Android TV auto-selection
            mDrawerLayout.addDrawerListener(new DrawerLayout.DrawerListener() {
                @Override
                public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
                    // Not needed
                }

                @Override
                public void onDrawerOpened(@NonNull View drawerView) {
                    // Auto-select current item when drawer opens
                    try {
                        Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 1);
                        if (currentFragment instanceof FragmentSettings) {
                            // For settings fragment, use a simpler approach to avoid crashes
                            selectCurrentDrawerItemForSettings();
                        } else {
                            // For other fragments, use the full auto-selection logic
                            selectCurrentDrawerItem();
                        }
                    } catch (Exception e) {
                        // Safety catch to prevent crashes
                        Log.e(TAG, "Error in drawer auto-selection", e);
                    }
                }

                @Override
                public void onDrawerClosed(@NonNull View drawerView) {
                    // Not needed
                }

                @Override
                public void onDrawerStateChanged(int newState) {
                    // Not needed
                }
            });

            mDrawerToggle.syncState();

            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }

        smallPlayerFragment = (FragmentPlayerSmall) mFragmentManager.findFragmentById(R.id.fragment_player_small);
        fullPlayerFragment = (FragmentPlayerFull) mFragmentManager.findFragmentById(R.id.fragment_player_full);

        if (smallPlayerFragment == null || fullPlayerFragment == null) {
            smallPlayerFragment = new FragmentPlayerSmall();
            fullPlayerFragment = new FragmentPlayerFull();

            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            // Hide it at start to make .onHiddenChanged be called on first show
            fragmentTransaction.hide(fullPlayerFragment);
            fragmentTransaction.replace(R.id.fragment_player_small, smallPlayerFragment);
            fragmentTransaction.replace(R.id.fragment_player_full, fullPlayerFragment);
            fragmentTransaction.commit();
        }

        smallPlayerFragment.setCallback(new FragmentPlayerSmall.Callback() {
            @Override
            public void onToggle() {
                toggleBottomSheetState();
            }
        });
        fullPlayerFragment.setTouchInterceptListener(new FragmentPlayerFull.TouchInterceptListener() {
            @Override
            public void requestDisallowInterceptTouchEvent(boolean disallow) {
                findViewById(R.id.bottom_sheet).getParent().requestDisallowInterceptTouchEvent(disallow);
            }
        });

        // Disable ability of ToolBar to follow bottom sheet because it doesn't work well with
        // our custom RecyclerAwareNestedScrollView
        CoordinatorLayout.LayoutParams coordinatorLayoutParams = (CoordinatorLayout.LayoutParams) appBarLayout.getLayoutParams();
        AppBarLayout.Behavior appBarLayoutBehavior = new AppBarLayout.Behavior() {
            @Override
            public boolean onStartNestedScroll(CoordinatorLayout parent, AppBarLayout child, View directTargetChild, View target, int nestedScrollAxes, int type) {
                return playerBottomSheet.getState() == BottomSheetBehavior.STATE_COLLAPSED;
            }
        };

        coordinatorLayoutParams.setBehavior(appBarLayoutBehavior);

        playerBottomSheet = BottomSheetBehavior.from(findViewById(R.id.bottom_sheet));
        playerBottomSheet.setBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            private int oldState = BottomSheetBehavior.STATE_COLLAPSED;

            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                // Prevent bottom sheet from minimizing if its content isn't scrolled to the top
                // Essentially this is a cheap hack to prevent bottom sheet from being dragged by non-scrolling elements.
                if (newState == BottomSheetBehavior.STATE_DRAGGING && oldState == BottomSheetBehavior.STATE_EXPANDED) {
                    if (fullPlayerFragment.isScrolled()) {
                        playerBottomSheet.setState(BottomSheetBehavior.STATE_EXPANDED);
                        return;
                    }
                }

                // Small player should serve as header if full screen player is expanded.
                // Hide full screen player's fragment if it is not visible to reduce resource usage.

                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    if (smallPlayerFragment.getContext() == null)
                        return;

                    appBarLayout.setExpanded(false);
                    smallPlayerFragment.setRole(FragmentPlayerSmall.Role.HEADER);

                    FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
                    fragmentTransaction.hide(mFragmentManager.findFragmentById(R.id.containerView));
                    fragmentTransaction.commit();
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    appBarLayout.setExpanded(true);
                    smallPlayerFragment.setRole(FragmentPlayerSmall.Role.PLAYER);
                    fullPlayerFragment.resetScroll();

                    FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
                    fragmentTransaction.hide(fullPlayerFragment);
                    fragmentTransaction.commit();
                }

                if (oldState == BottomSheetBehavior.STATE_EXPANDED && newState != BottomSheetBehavior.STATE_EXPANDED) {
                    FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
                    fragmentTransaction.show(mFragmentManager.findFragmentById(R.id.containerView));
                    fragmentTransaction.commit();
                }

                if (oldState == BottomSheetBehavior.STATE_COLLAPSED && newState != oldState) {
                    fullPlayerFragment.init();

                    FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
                    fragmentTransaction.show(fullPlayerFragment);
                    fragmentTransaction.commit();
                }

                oldState = newState;
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {

            }
        });

        ((RadioDroidApp) getApplication()).getCastHandler().onCreate(this);

        setupBackPressedCallback();
        setupStartUpFragment();
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem menuItem) {
        // If menuItem == null method was executed manually
        if (menuItem != null)
            selectedMenuItem = menuItem.getItemId();

        if (playerBottomSheet.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            playerBottomSheet.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }

        if (mSearchView != null) {
            mSearchView.clearFocus();
            mSearchView.setFocusableInTouchMode(true);
        }

        mDrawerLayout.closeDrawers();
        Fragment f = null;
        String backStackTag = String.valueOf(selectedMenuItem);

        switch (selectedMenuItem) {
            case R.id.nav_item_stations:
                f = new FragmentTabs();
                break;
            case R.id.nav_item_starred:
                f = new FragmentStarred();
                break;
            case R.id.nav_item_history:
                f = new FragmentHistory();
                break;
            case R.id.nav_item_alarm:
                f = new FragmentAlarm();
                break;
            case R.id.nav_item_settings:
                f = new FragmentSettings();
                break;
            default:
        }

        // Without "Immediate", "Settings" fragment may become forever stuck in limbo receiving onResume.
        // I'm not sure why.
        mFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
        if (useBottomNavigation())
            fragmentTransaction.replace(R.id.containerView, f).commit();
        else
            fragmentTransaction.replace(R.id.containerView, f).addToBackStack(backStackTag).commit();

        // User selected a menuItem. Let's hide progressBar
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ActivityMain.ACTION_HIDE_LOADING));
        invalidateOptionsMenu();
        checkMenuItems();

        appBarLayout.setExpanded(true);

        return false;
    }

    private void setupBackPressedCallback() {
        OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (playerBottomSheet.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                    playerBottomSheet.setState(BottomSheetBehavior.STATE_COLLAPSED);
                    return;
                }

                int backStackCount = mFragmentManager.getBackStackEntryCount();
                FragmentManager.BackStackEntry backStackEntry;

                if (backStackCount > 0) {
                    // FRAGMENT_FROM_BACKSTACK value added as a backstack name for non-root fragments like Recordings, About, etc
                    backStackEntry = mFragmentManager.getBackStackEntryAt(mFragmentManager.getBackStackEntryCount() - 1);
                    if (backStackEntry.getName().equals("SearchPreferenceFragment")) {
                        // Disable this callback temporarily and call system back
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                        return;
                    }
                    int parsedId = Integer.parseInt(backStackEntry.getName());
                    if (parsedId == FRAGMENT_FROM_BACKSTACK) {
                        // Disable this callback temporarily and call system back
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                        invalidateOptionsMenu();
                        return;
                    }
                }

                // Don't support backstack with BottomNavigationView
                if (useBottomNavigation()) {
                    // I'm giving 3 seconds on making a choice
                    if (lastExitTry != null && new Date().getTime() < lastExitTry.getTime() + 3 * 1000) {
                        PlayerServiceUtil.shutdownService();
                        finish();
                    } else {
                        Toast.makeText(ActivityMain.this, R.string.alert_press_back_to_exit, Toast.LENGTH_SHORT).show();
                        lastExitTry = new Date();
                        return;
                    }
                }

                if (backStackCount > 1) {
                    backStackEntry = mFragmentManager.getBackStackEntryAt(mFragmentManager.getBackStackEntryCount() - 2);

                    selectedMenuItem = Integer.parseInt(backStackEntry.getName());

                    if (!useBottomNavigation()) {
                        mNavigationView.setCheckedItem(selectedMenuItem);
                    }
                    invalidateOptionsMenu();

                } else {
                    finish();
                    return;
                }
                // Disable this callback temporarily and call system back
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        };

        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
    }

    public boolean isRunningOnTV() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        return uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private boolean useBottomNavigation() {
        // Always use drawer navigation on Android TV for better remote control navigation
        if (isRunningOnTV()) {
            return false;
        }
        return Utils.bottomNavigationEnabled(this);
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "on request permissions result:" + requestCode);
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case PERM_REQ_STORAGE_FAV_LOAD: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    LoadFavourites();
                } else {
                    Log.w(TAG, "permission not granted -> simple load");
                    LoadFavouritesSimple();
                }
                return;
            }
            case PERM_REQ_STORAGE_FAV_SAVE: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    SaveFavourites();
                } else {
                    Log.w(TAG, "permission not granted -> simple save");
                    SaveFavouritesSimple();
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (!PlayerServiceUtil.isNotificationActive()) {
            /* If at this point if for whatever reason we have the service without a notification,
             * we must shut it down because user doesn't have a way to interact with it.
             * This is a safeguard since such service should have been destroyed in onPause()
             */
            PlayerServiceUtil.shutdownService();
        }
    }

    @Override
    protected void onPause() {
        SharedPreferences.Editor ed = sharedPref.edit();
        ed.putInt("last_selectedMenuItem", selectedMenuItem);
        ed.apply();

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "PAUSED");
        }

        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);

        super.onPause();

        if (PlayerServiceUtil.getPlayerState() == PlayState.Idle) {
            PlayerServiceUtil.shutdownService();
        }

        CastHandler castHandler = ((RadioDroidApp) getApplication()).getCastHandler();
        castHandler.onPause();
        castHandler.setActivity(null);
    }

    private void handleIntent(@NonNull Intent intent) {
        String action = intent.getAction();
        final Bundle extras = intent.getExtras();
        if (extras == null) {
            return;
        }

        if (MediaSessionCallback.ACTION_PLAY_STATION_BY_UUID.equals(action)) {
            final Context context = getApplicationContext();
            final String stationUUID = extras.getString(EXTRA_STATION_UUID);
            if (TextUtils.isEmpty(stationUUID))
                return;
            intent.removeExtra(EXTRA_STATION_UUID); // mark intent as consumed
            RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
            final OkHttpClient httpClient = radioDroidApp.getHttpClient();

            new AsyncTask<Void, Void, DataRadioStation>() {
                @Override
                protected DataRadioStation doInBackground(Void... params) {
                    return Utils.getStationByUuid(httpClient, context, stationUUID);
                }

                @Override
                protected void onPostExecute(DataRadioStation station) {
                    if (!isFinishing()) {
                        if (station != null) {
                            Utils.showPlaySelection(radioDroidApp, station, getSupportFragmentManager());

                            Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 1);
                            if (currentFragment instanceof FragmentHistory) {
                                ((FragmentHistory) currentFragment).RefreshListGui();
                            }
                        }
                    }
                }
            }.execute();
        } else {
            final String searchTag = extras.getString(EXTRA_SEARCH_TAG);
            Log.d("MAIN", "received search request for tag 1: " + searchTag);
            if (searchTag != null) {
                Log.d("MAIN", "received search request for tag 2: " + searchTag);
                Search(StationsFilter.SearchStyle.ByTagExact, searchTag);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "RESUMED");
        }

        setupBroadcastReceiver();

        PlayerServiceUtil.startService(getApplicationContext());
        CastHandler castHandler = ((RadioDroidApp) getApplication()).getCastHandler();
        castHandler.onResume();
        castHandler.setActivity(this);

        if (playerBottomSheet.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            appBarLayout.setExpanded(false);
        }

        Intent intent = getIntent();
        if (intent != null) {
            handleIntent(intent);
            setIntent(null);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);

        final Toolbar myToolbar = findViewById(R.id.my_awesome_toolbar);
        menuItemSleepTimer = menu.findItem(R.id.action_set_sleep_timer);
        menuItemSearch = menu.findItem(R.id.action_search);
        menuItemDelete = menu.findItem(R.id.action_delete);
        menuItemSave = menu.findItem(R.id.action_save);
        menuItemLoad = menu.findItem(R.id.action_load);
        menuItemListView = menu.findItem(R.id.action_list_view);
        menuItemIconsView = menu.findItem(R.id.action_icons_view);
        menuItemAddAlarm = menu.findItem(R.id.action_add_alarm);
        menuItemMpd = menu.findItem(R.id.action_mpd);
        mSearchView = (SearchView) menuItemSearch.getActionView();
        mSearchView.setOnQueryTextListener(this);
        mSearchView.setFocusableInTouchMode(true);
        showSoftKeyboard(mSearchView);
        mSearchView.setOnQueryTextFocusChangeListener(new View.OnFocusChangeListener() {
            private int prevTabsVisibility = View.GONE;

            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    Log.d(TAG, "SearchView has focus");
                    prevTabsVisibility = tabsView.getVisibility();
                    tabsView.setVisibility(View.GONE);
                    if (isRunningOnTV()) {
                        showSoftKeyboard(mSearchView);
                    }
                } else {
                    tabsView.setVisibility(prevTabsVisibility);
                }

            }
        });

        menuItemSleepTimer.setVisible(false);
        menuItemSearch.setVisible(false);
        menuItemDelete.setVisible(false);
        menuItemSave.setVisible(false);
        menuItemLoad.setVisible(false);
        menuItemListView.setVisible(false);
        menuItemIconsView.setVisible(false);
        menuItemAddAlarm.setVisible(false);

        boolean mpd_is_visible = false;
        RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
        if (radioDroidApp != null) {
            MPDClient mpdClient = radioDroidApp.getMpdClient();
            if (mpdClient != null) {
                MPDServersRepository repository = mpdClient.getMpdServersRepository();
                mpd_is_visible = !repository.isEmpty();
            }
        }
        menuItemMpd.setVisible(mpd_is_visible);

        switch (selectedMenuItem) {
            case R.id.nav_item_stations: {
                menuItemSleepTimer.setVisible(true);
                menuItemSearch.setVisible(true);
                menuItemSearch.getActionView().setActivated(true);
                menuItemSearch.getActionView().setFocusable(true);
                ((SearchView) menuItemSearch.getActionView()).setIconifiedByDefault(false);
//                ((SearchView) menuItemSearch.getActionView()).setIconified(false);
                myToolbar.setTitle(R.string.nav_item_stations);
                break;
            }
            case R.id.nav_item_starred: {
                menuItemSleepTimer.setVisible(true);
                //menuItemSearch.setVisible(true);
                menuItemSave.setVisible(true);
                menuItemLoad.setVisible(true);
                menuItemSave.setTitle(R.string.nav_item_save_playlist);

                if (sharedPref.getBoolean("icons_only_favorites_style", false)) {
                    menuItemListView.setVisible(true);
                } else if (sharedPref.getBoolean("load_icons", false)) {
                    menuItemIconsView.setVisible(true);
                }
                if (radioDroidApp.getFavouriteManager().isEmpty()) {
                    menuItemDelete.setVisible(false);
                } else {
                    menuItemDelete.setVisible(true).setTitle(R.string.action_delete_favorites);
                }
                myToolbar.setTitle(R.string.nav_item_starred);
                break;
            }
            case R.id.nav_item_history: {
                menuItemSleepTimer.setVisible(true);
                //menuItemSearch.setVisible(true);
                menuItemSave.setVisible(true);
                menuItemSave.setTitle(R.string.nav_item_save_history_playlist);

                if (!radioDroidApp.getHistoryManager().isEmpty()) {
                    menuItemDelete.setVisible(true).setTitle(R.string.action_delete_history);
                }
                myToolbar.setTitle(R.string.nav_item_history);
                break;
            }
            case R.id.nav_item_alarm: {
                menuItemAddAlarm.setVisible(true);
                myToolbar.setTitle(R.string.nav_item_alarm);
                break;
            }
 /* settings fragment sets the toolbar title depending on the current preference screen
            case R.id.nav_item_settings: {
                myToolbar.setTitle(R.string.nav_item_settings);
                break;
            }
 */
        }

        ((RadioDroidApp) getApplication()).getCastHandler().getRouteItem(getApplicationContext(), menu);

        return true;
    }

    public void showSoftKeyboard(View view) {
        view.requestFocus();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && view.getWindowInsetsController() != null) {
            view.getWindowInsetsController().show(WindowInsets.Type.ime());
        } else {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);

        if (requestCode == ACTION_SAVE_FILE && resultCode == RESULT_OK) {
            Uri uri = null;
            if (resultData != null) {
                uri = resultData.getData();
                Log.d(TAG, "Choosen save path: " + uri);
                RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                FavouriteManager favouriteManager = radioDroidApp.getFavouriteManager();
                HistoryManager historyManager = radioDroidApp.getHistoryManager();
                try {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    OutputStreamWriter writer = new OutputStreamWriter(os);
                    if (selectedMenuItem == R.id.nav_item_starred) {
                        favouriteManager.SaveM3UWriter(writer);
                    } else if (selectedMenuItem == R.id.nav_item_history) {
                        historyManager.SaveM3UWriter(writer);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Unable to write to file " + e);
                }
            }
        }
        if (requestCode == ACTION_LOAD_FILE && resultCode == RESULT_OK) {
            Uri uri = null;
            if (resultData != null) {
                uri = resultData.getData();
                Log.d(TAG, "Choosen load path: " + uri);
                RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                FavouriteManager favouriteManager = radioDroidApp.getFavouriteManager();
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    InputStreamReader reader = new InputStreamReader(is);
                    // Extract display name from SAF Uri
                    String displayName = "";
                    if (uri != null) {
                        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                            if (cursor != null && cursor.moveToFirst()) {
                                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                                if (nameIndex >= 0) {
                                    displayName = cursor.getString(nameIndex);
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Unable to get display name from SAF Uri", e);
                        }
                    }
                    favouriteManager.LoadM3USimple(reader, displayName);
                } catch (Exception e) {
                    Log.e(TAG, "Unable to load to file " + e);
                }
            }
        }
    }

    @Override
    public void onFileSelected(FileDialog dialog, File file) {
        try {
            Log.i("MAIN", "save to " + file.getParent() + "/" + file.getName());
            RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
            FavouriteManager favouriteManager = radioDroidApp.getFavouriteManager();
            HistoryManager historyManager = radioDroidApp.getHistoryManager();

            if (dialog instanceof SaveFileDialog) {
                if (selectedMenuItem == R.id.nav_item_starred) {
                    favouriteManager.SaveM3U(file.getParent(), file.getName());
                } else if (selectedMenuItem == R.id.nav_item_history) {
                    historyManager.SaveM3U(file.getParent(), file.getName());
                }
            } else if (dialog instanceof OpenFileDialog) {
                favouriteManager.LoadM3U(file.getParent(), file.getName());
            }
        } catch (Exception e) {
            Log.e("MAIN", e.toString());
        }
    }

    void SaveFavourites() {
        SaveFileDialog dialog = new SaveFileDialog();
        dialog.setStyle(DialogFragment.STYLE_NO_TITLE, Utils.getThemeResId(this));
        Bundle args = new Bundle();
        args.putString(FileDialog.EXTENSION, ".m3u"); // file extension is optional
        dialog.setArguments(args);
        dialog.show(getSupportFragmentManager(), SaveFileDialog.class.getName());
    }

    void SaveFavouritesSimple() {
        // Use Storage Access Framework to let the user choose where to save the playlist
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/x-mpegurl");
        intent.putExtra(Intent.EXTRA_TITLE, "playlist.m3u");
        startActivityForResult(intent, ACTION_SAVE_FILE);
    }

    void LoadFavourites() {
        // Use Storage Access Framework to let the user pick any .m3u playlist file
        LoadFavouritesSimple();
    }

    void LoadFavouritesSimple() {
        // Use Storage Access Framework to open a playlist file
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/x-mpegurl");
        intent.putExtra(Intent.EXTRA_TITLE, "playlist.m3u");
        startActivityForResult(intent, ACTION_LOAD_FILE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case android.R.id.home:
                mDrawerLayout.openDrawer(GravityCompat.START);  // OPEN DRAWER
                return true;
            case R.id.action_save:
                try {
                    if (Utils.verifyStoragePermissions(this, PERM_REQ_STORAGE_FAV_SAVE)) {
                        SaveFavourites();
                    }
                } catch (Exception e) {
                    Log.e("MAIN", e.toString());
                }

                return true;
            case R.id.action_load:
                try {
                    if (Utils.verifyStoragePermissions(this, PERM_REQ_STORAGE_FAV_LOAD)) {
                        LoadFavourites();
                    }
                } catch (Exception e) {
                    Log.e("MAIN", e.toString());
                }
                return true;
            case R.id.action_set_sleep_timer:
                changeTimer();
                return true;
            case R.id.action_mpd:
                selectMPDServer();
                return true;
            case R.id.action_delete:
                if (selectedMenuItem == R.id.nav_item_history) {
                    new AlertDialog.Builder(this)
                            .setMessage(this.getString(R.string.alert_delete_history))
                            .setCancelable(true)
                            .setPositiveButton(this.getString(R.string.yes), new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                                    HistoryManager historyManager = radioDroidApp.getHistoryManager();

                                    historyManager.clear();

                                    Toast toast = Toast.makeText(getApplicationContext(), getString(R.string.notify_deleted_history), Toast.LENGTH_SHORT);
                                    toast.show();
                                    recreate();
                                }
                            })
                            .setNegativeButton(this.getString(R.string.no), null)
                            .show();
                }
                if (selectedMenuItem == R.id.nav_item_starred) {
                    new AlertDialog.Builder(this)
                            .setMessage(this.getString(R.string.alert_delete_favorites))
                            .setCancelable(true)
                            .setPositiveButton(this.getString(R.string.yes), new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
                                    FavouriteManager favouriteManager = radioDroidApp.getFavouriteManager();

                                    favouriteManager.clear();

                                    Toast toast = Toast.makeText(getApplicationContext(), getString(R.string.notify_deleted_favorites), Toast.LENGTH_SHORT);
                                    toast.show();
                                    recreate();
                                }
                            })
                            .setNegativeButton(this.getString(R.string.no), null)
                            .show();
                }
                return true;
            case R.id.action_list_view:
                sharedPref.edit().putBoolean("icons_only_favorites_style", false).apply();
                recreate();
                return true;
            case R.id.action_icons_view:
                sharedPref.edit().putBoolean("icons_only_favorites_style", true).apply();
                recreate();
                return true;
            case R.id.action_add_alarm:
                TimePickerFragment newFragment = new TimePickerFragment();
                newFragment.setCallback(this);
                newFragment.show(getSupportFragmentManager(), "timePicker");
                return true;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    public void toggleBottomSheetState() {
        if (playerBottomSheet.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            playerBottomSheet.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            playerBottomSheet.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    @Override
    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
        RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
        HistoryManager historyManager = radioDroidApp.getHistoryManager();
        Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 2);
        if (historyManager.size() > 0 && currentFragment instanceof FragmentAlarm) {
            DataRadioStation station = historyManager.getList().get(0);
            ((FragmentAlarm) currentFragment).getRam().add(station, hourOfDay, minute);
        }
    }

    private void setupStartUpFragment() {
        // This will restore fragment that was shown before activity was recreated
        if (instanceStateWasSaved) {
            invalidateOptionsMenu();
            checkMenuItems();
            return;
        }

        RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
        HistoryManager hm = radioDroidApp.getHistoryManager();
        FavouriteManager fm = radioDroidApp.getFavouriteManager();

        final String startupAction = sharedPref.getString("startup_action", getResources().getString(R.string.startup_show_history));

        if (startupAction.equals(getResources().getString(R.string.startup_show_history)) && hm.isEmpty()) {
            selectMenuItem(R.id.nav_item_stations);
            return;
        }

        if (startupAction.equals(getResources().getString(R.string.startup_show_favorites)) && fm.isEmpty()) {
            selectMenuItem(R.id.nav_item_stations);
            return;
        }

        if (startupAction.equals(getResources().getString(R.string.startup_show_history))) {
            selectMenuItem(R.id.nav_item_history);
        } else if (startupAction.equals(getResources().getString(R.string.startup_show_favorites))) {
            selectMenuItem(R.id.nav_item_starred);
        } else if (startupAction.equals(getResources().getString(R.string.startup_show_all_stations)) || selectedMenuItem < 0) {
            selectMenuItem(R.id.nav_item_stations);
        } else {
            selectMenuItem(selectedMenuItem);
        }
    }

    private void selectMenuItem(int itemId) {
        MenuItem item;
        if (useBottomNavigation())
            item = mBottomNavigationView.getMenu().findItem(itemId);
        else
            item = mNavigationView.getMenu().findItem(itemId);

        if (item != null) {
            onNavigationItemSelected(item);
        } else {
            selectedMenuItem = R.id.nav_item_stations;
            onNavigationItemSelected(null);
        }
    }

    private void checkMenuItems() {
        if (mBottomNavigationView.getMenu().findItem(selectedMenuItem) != null)
            mBottomNavigationView.getMenu().findItem(selectedMenuItem).setChecked(true);

        if (mNavigationView.getMenu().findItem(selectedMenuItem) != null)
            mNavigationView.getMenu().findItem(selectedMenuItem).setChecked(true);
    }

    public void Search(StationsFilter.SearchStyle searchStyle, String query) {
        Log.d("MAIN", "Search() searchstyle=" + searchStyle + " query=" + query);
        Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 1);
        if (currentFragment instanceof FragmentTabs) {
            ((FragmentTabs) currentFragment).Search(searchStyle, query);
        } else {
            String backStackTag = String.valueOf(R.id.nav_item_stations);
            FragmentTabs f = new FragmentTabs();
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            if (useBottomNavigation()) {
                fragmentTransaction.replace(R.id.containerView, f).commit();
                mBottomNavigationView.getMenu().findItem(R.id.nav_item_stations).setChecked(true);
            } else {
                fragmentTransaction.replace(R.id.containerView, f).addToBackStack(backStackTag).commit();
                mNavigationView.getMenu().findItem(R.id.nav_item_stations).setChecked(true);
            }

            f.Search(searchStyle, query);
            selectedMenuItem = R.id.nav_item_stations;
            invalidateOptionsMenu();
        }

    }

    public void SearchStations(@NonNull String query) {
        Log.d("MAIN", "SearchStations() " + query);
        Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 1);
        if (currentFragment instanceof IFragmentSearchable) {
            ((IFragmentSearchable) currentFragment).Search(StationsFilter.SearchStyle.ByName, query);
        }
    }

//    public void togglePlayer() {
//        FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
//        if (smallPlayerFragment.isDetached()) {
//            fragmentTransaction.attach(smallPlayerFragment);
//            fragmentTransaction.detach(fullPlayerFragment);
//        } else {
//            fragmentTransaction.attach(fullPlayerFragment);
//            fragmentTransaction.detach(smallPlayerFragment);
//        }
//
//        fragmentTransaction.commit();
//    }

    @Override
    public boolean onQueryTextSubmit(String query) {
//        String queryEncoded;
//        try {
//            mSearchView.setQuery("", false);
//            mSearchView.clearFocus();
//            mSearchView.setIconified(true);
//            queryEncoded = URLEncoder.encode(query, "utf-8");
//            queryEncoded = queryEncoded.replace("+", "%20");
//            SearchStations(query);
//        } catch (UnsupportedEncodingException e) {
//            e.printStackTrace();
//        }
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        SearchStations(newText);
        return true;
    }

    private void showMeteredConnectionDialog(@NonNull Runnable playFunc) {
        Resources res = this.getResources();
        String title = res.getString(R.string.alert_metered_connection_title);
        String text = res.getString(R.string.alert_metered_connection_message);
        meteredConnectionAlertDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> playFunc.run())
                .setOnDismissListener(dialog -> meteredConnectionAlertDialog = null)
                .create();

        meteredConnectionAlertDialog.show();
    }

    private void setupBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_HIDE_LOADING);
        filter.addAction(ACTION_SHOW_LOADING);
        filter.addAction(PlayerService.PLAYER_SERVICE_STATE_CHANGE);
        filter.addAction(PlayerService.PLAYER_SERVICE_METERED_CONNECTION);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(ACTION_HIDE_LOADING)) {
                    hideLoadingIcon();
                } else if (intent.getAction().equals(ACTION_SHOW_LOADING)) {
                    showLoadingIcon();
                } else if (intent.getAction().equals(PlayerService.PLAYER_SERVICE_METERED_CONNECTION)) {
                    if (meteredConnectionAlertDialog != null) {
                        meteredConnectionAlertDialog.cancel();
                        meteredConnectionAlertDialog = null;
                    }

                    PlayerType playerType = intent.getParcelableExtra(PlayerService.PLAYER_SERVICE_METERED_CONNECTION_PLAYER_TYPE);

                    switch (playerType) {
                        case RADIODROID:
                            showMeteredConnectionDialog(() -> Utils.play((RadioDroidApp) getApplication(), PlayerServiceUtil.getCurrentStation()));
                            break;
                        case EXTERNAL:
                            DataRadioStation currentStation = PlayerServiceUtil.getCurrentStation();
                            if (currentStation != null) {
                                showMeteredConnectionDialog(() -> PlayStationTask.playExternal(currentStation, ActivityMain.this).execute());
                            }
                            break;
                        default:
                            Log.e(TAG, String.format("broadcastReceiver unexpected PlayerType '%s'", playerType));
                    }
                } else if (intent.getAction().equals(PlayerService.PLAYER_SERVICE_STATE_CHANGE)) {
                    if (PlayerServiceUtil.isPlaying()) {
                        if (meteredConnectionAlertDialog != null) {
                            meteredConnectionAlertDialog.cancel();
                            meteredConnectionAlertDialog = null;
                        }
                    }
                }
            }
        };

        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, filter);
    }

    // Loading listener
    private void showLoadingIcon() {
        View progressBar = findViewById(R.id.progressBarLoading);
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
    }

    private void hideLoadingIcon() {
        View progressBar = findViewById(R.id.progressBarLoading);
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
    }

    public void focusSearchView() {
        focusSearchView(true); // TV-only by default
    }
    
    public void focusSearchView(boolean tvOnly) {
        Log.d(TAG, "focusSearchView called, mSearchView=" + (mSearchView != null) + ", isTV=" + isRunningOnTV() + ", menuItemSearch=" + (menuItemSearch != null) + ", tvOnly=" + tvOnly);
        
        if (mSearchView != null && (!tvOnly || isRunningOnTV())) {
            Log.d(TAG, "Attempting to focus search view" + (tvOnly ? " on TV" : ""));
            
            // Ensure search menu item is visible and expand its action view
            if (menuItemSearch != null) {
                Log.d(TAG, "Making search menu item visible and expanding action view");
                menuItemSearch.setVisible(true);
                
                // Expand the action view to show the search field
                boolean expanded = menuItemSearch.expandActionView();
                Log.d(TAG, "Search menu item expansion result: " + expanded);
                
                if (expanded) {
                    // Configure the search view after expansion
                    mSearchView.setIconifiedByDefault(false);
                    mSearchView.setIconified(false);
                    
                    // Focus the search view
                    boolean focused = mSearchView.requestFocus();
                    Log.d(TAG, "Search view focus result: " + focused);
                    
                    // Show keyboard - try a delayed approach for better compatibility
                    mSearchView.post(() -> {
                        showSoftKeyboard(mSearchView);
                        Log.d(TAG, "Delayed keyboard show attempt completed");
                    });
                    
                    Log.d(TAG, "Search view setup completed");
                } else {
                    Log.d(TAG, "Failed to expand search action view");
                }
            } else {
                Log.d(TAG, "menuItemSearch is null");
            }
        } else {
            Log.d(TAG, "Cannot focus search view - mSearchView=" + (mSearchView != null) + ", isTV=" + isRunningOnTV() + ", tvOnly=" + tvOnly);
        }
    }

    private void changeTimer() {
        final AlertDialog.Builder seekDialog = new AlertDialog.Builder(this);
        View seekView = View.inflate(this, R.layout.layout_timer_chooser, null);

        seekDialog.setTitle(R.string.sleep_timer_title);
        seekDialog.setView(seekView);

        final TextView seekTextView = seekView.findViewById(R.id.timerTextView);
        final SeekBar seekBar = seekView.findViewById(R.id.timerSeekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                seekTextView.setText(String.valueOf(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        long currenTimerSeconds = PlayerServiceUtil.getTimerSeconds();
        long currentTimer;
        if (currenTimerSeconds <= 0) {
            currentTimer = sharedPref.getInt("sleep_timer_default_minutes", 10);
        } else if (currenTimerSeconds < 60) {
            currentTimer = 1;
        } else {
            currentTimer = currenTimerSeconds / 60;
        }
        seekBar.setProgress((int) currentTimer);
        seekDialog.setPositiveButton(R.string.sleep_timer_apply, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                PlayerServiceUtil.clearTimer();
                PlayerServiceUtil.addTimer(seekBar.getProgress() * 60);
                sharedPref.edit().putInt("sleep_timer_default_minutes", seekBar.getProgress()).apply();
            }
        });

        seekDialog.setNegativeButton(R.string.sleep_timer_clear, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                PlayerServiceUtil.clearTimer();
            }
        });

        seekDialog.create();
        seekDialog.show();
    }

    private void selectMPDServer() {
        RadioDroidApp radioDroidApp = (RadioDroidApp) getApplication();
        Utils.showMpdServersDialog(radioDroidApp, getSupportFragmentManager(), null);
    }

    public final Toolbar getToolbar() {
        return findViewById(R.id.my_awesome_toolbar);
    }

    @Override
    public void onSearchResultClicked(SearchPreferenceResult result) {
        result.closeSearchPage(this);
        getSupportFragmentManager().popBackStack();
        FragmentSettings f = FragmentSettings.openNewSettingsSubFragment(this, result.getScreen());
        result.highlight(f);
    }

    @Override
    public void invalidateOptionsMenuForCast() {
        invalidateOptionsMenu();
    }

    @SuppressWarnings("GestureBackNavigation")
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Handle TV remote control keys
        if (isRunningOnTV()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_TV_CONTENTS_MENU:
                case KeyEvent.KEYCODE_0:
                    // Open navigation drawer when menu button is pressed on TV
                    if (!mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                        mDrawerLayout.openDrawer(GravityCompat.START);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    // Close drawer first if it's open (TV remote only)
                    // Note: This handles TV remote KEYCODE_BACK events specifically for drawer management
                    // Main back navigation is handled by OnBackPressedDispatcher
                    if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                        mDrawerLayout.closeDrawer(GravityCompat.START);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_UP:
                    // Navigate to previous item in list
                    if (navigateInCurrentList(-1)) {
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    // Navigate to next item in list
                    if (navigateInCurrentList(1)) {
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    // Skip to next station
                    PlayerServiceUtil.skipToNext();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    // Skip to previous station
                    PlayerServiceUtil.skipToPrevious();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Navigate in the current list fragment when channel up/down is pressed on TV remote
     *
     * @param direction -1 for previous item, 1 for next item
     * @return true if navigation was handled, false otherwise
     */
    private boolean navigateInCurrentList(int direction) {
        Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 1);

        // Handle different fragment types that contain lists
        if (currentFragment instanceof FragmentStarred) {
            return navigateInRecyclerView(((FragmentStarred) currentFragment).getRecyclerView(), direction);
        } else if (currentFragment instanceof FragmentHistory) {
            return navigateInRecyclerView(((FragmentHistory) currentFragment).getRecyclerView(), direction);
        } else if (currentFragment instanceof FragmentTabs) {
            // FragmentTabs contains nested fragments, get the current tab's fragment
            Fragment activeTabFragment = ((FragmentTabs) currentFragment).getCurrentFragment();
            if (activeTabFragment instanceof net.programmierecke.radiodroid2.station.FragmentStations) {
                return navigateInRecyclerView(((net.programmierecke.radiodroid2.station.FragmentStations) activeTabFragment).getRecyclerView(), direction);
            }
        } else if (currentFragment instanceof net.programmierecke.radiodroid2.station.FragmentStations) {
            return navigateInRecyclerView(((net.programmierecke.radiodroid2.station.FragmentStations) currentFragment).getRecyclerView(), direction);
        }

        return false;
    }

    /**
     * Simplified drawer focus method specifically for settings fragment to avoid crashes
     */
    private void selectCurrentDrawerItemForSettings() {
        // Hide virtual keyboard on Android TV
        if (mSearchView != null) {
            mSearchView.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(mSearchView.getWindowToken(), 0);
        }

        if (mNavigationView != null) {
            // Post with delay to ensure drawer is fully opened
            try {
                // Clear all checked items first, then check only the settings item
                mNavigationView.getMenu();
                Menu menu = mNavigationView.getMenu();

                // Clear all items first
                for (int i = 0; i < menu.size(); i++) {
                    MenuItem item = menu.getItem(i);
                    if (item != null) {
                        item.setChecked(false);
                    }
                }

                // Then set only the settings item as checked
                MenuItem settingsItem = menu.findItem(R.id.nav_item_settings);
                if (settingsItem != null) {
                    settingsItem.setChecked(true);
                }

                // Use a simpler approach to focus the last item (settings)
                focusLastNavigationItem();
            } catch (Exception e) {
                Log.e(TAG, "Error focusing settings in drawer", e);
            }
        }
    }

    /**
     * Focus the last item in the navigation drawer (settings item)
     */
    private void focusLastNavigationItem() {
        if (mNavigationView == null) return;

        // Find the RecyclerView inside the NavigationView
        for (int i = 0; i < mNavigationView.getChildCount(); i++) {
            View child = mNavigationView.getChildAt(i);
            if (child instanceof androidx.recyclerview.widget.RecyclerView recyclerView) {

                if (recyclerView.getAdapter() != null) {
                    int lastPosition = recyclerView.getAdapter().getItemCount() - 1;
                    if (lastPosition >= 0) {
                        // Scroll to and focus the last position
                        recyclerView.scrollToPosition(lastPosition);
                        recyclerView.post(() -> {
                            androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                                    recyclerView.findViewHolderForAdapterPosition(lastPosition);
                            if (vh != null && vh.itemView != null) {
                                vh.itemView.requestFocus();
                            }
                        });
                    }
                }
                break;
            }
        }
    }

    /**
     * Select the current fragment's corresponding item in the navigation drawer on Android TV
     */
    private void selectCurrentDrawerItem() {
        // Hide virtual keyboard on Android TV
        if (mSearchView != null) {
            mSearchView.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(mSearchView.getWindowToken(), 0);
        }

        if (mNavigationView != null && mNavigationView.getMenu() != null) {
            // Get the menu item ID that corresponds to the current fragment
            int currentMenuItemId = getCurrentFragmentMenuItemId();

            // Clear all checked items first, then check only the current item
            Menu menu = mNavigationView.getMenu();
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (item != null) {
                    item.setChecked(false);
                }
            }

            // Set only the current fragment's menu item as checked
            MenuItem currentItem = menu.findItem(currentMenuItemId);
            if (currentItem != null) {
                currentItem.setChecked(true);
            }

            focusNavigationMenuItemByFragment(currentMenuItemId);
        }
    }

    /**
     * Get the menu item ID that corresponds to the currently active fragment
     */
    private int getCurrentFragmentMenuItemId() {
        try {
            if (mFragmentManager == null || mFragmentManager.getFragments().isEmpty()) {
                return R.id.nav_item_stations; // Safe default
            }

            Fragment currentFragment = mFragmentManager.getFragments().get(mFragmentManager.getFragments().size() - 1);

            if (currentFragment == null) {
                return R.id.nav_item_stations; // Safe default
            }

            // Handle different fragment types to determine corresponding menu item
            if (currentFragment instanceof FragmentStarred) {
                return R.id.nav_item_starred;
            } else if (currentFragment instanceof FragmentHistory) {
                return R.id.nav_item_history;
            } else if (currentFragment instanceof FragmentAlarm) {
                return R.id.nav_item_alarm;
            } else if (currentFragment instanceof FragmentSettings) {
                return R.id.nav_item_settings;
            } else if (currentFragment instanceof FragmentTabs) {
                return R.id.nav_item_stations;
            }

            // Default to stations if we can't determine the fragment type
            return R.id.nav_item_stations;
        } catch (Exception e) {
            // Safety fallback in case of any exception
            return R.id.nav_item_stations;
        }
    }

    /**
     * Focus the navigation menu item that corresponds to a specific fragment
     */
    private void focusNavigationMenuItemByFragment(int menuItemId) {
        if (mNavigationView == null) return;

        // Find the RecyclerView inside the NavigationView
        for (int i = 0; i < mNavigationView.getChildCount(); i++) {
            View child = mNavigationView.getChildAt(i);
            if (child instanceof androidx.recyclerview.widget.RecyclerView recyclerView) {

                // Find the position of our menu item in the navigation menu
                Menu menu = mNavigationView.getMenu();
                int targetPosition = -1;
                for (int j = 0; j < menu.size(); j++) {
                    if (menu.getItem(j).getItemId() == menuItemId) {
                        targetPosition = j;
                        break;
                    }
                }

                if (targetPosition >= 0) {
                    // Adjust position - focus was one below the correct item, so add 1
                    int adjustedPosition = targetPosition + 1;

                    // Ensure we don't exceed the adapter bounds
                    int adapterItemCount = recyclerView.getAdapter() != null ? recyclerView.getAdapter().getItemCount() : 0;
                    final int finalTargetPosition = Math.min(adjustedPosition, adapterItemCount - 1);
                    final int originalTargetPosition = targetPosition; // Make final for lambda

                    // Try to focus the view at this adapter position
                    androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder =
                            recyclerView.findViewHolderForAdapterPosition(finalTargetPosition);
                    if (viewHolder != null && viewHolder.itemView != null) {
                        viewHolder.itemView.requestFocus();
                    } else {
                        // If view holder not found, scroll to position and try again
                        recyclerView.scrollToPosition(finalTargetPosition);
                        recyclerView.post(() -> {
                            androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                                    recyclerView.findViewHolderForAdapterPosition(finalTargetPosition);
                            if (vh != null && vh.itemView != null) {
                                vh.itemView.requestFocus();
                            } else {
                                // Last resort: try the original position without adjustment
                                androidx.recyclerview.widget.RecyclerView.ViewHolder fallbackVh =
                                        recyclerView.findViewHolderForAdapterPosition(originalTargetPosition);
                                if (fallbackVh != null && fallbackVh.itemView != null) {
                                    fallbackVh.itemView.requestFocus();
                                }
                            }
                        });
                    }
                }
                break;
            }
        }
    }

    /**
     * Navigate within a RecyclerView
     *
     * @param recyclerView the RecyclerView to navigate in
     * @param direction    -1 for previous item, 1 for next item
     * @return true if navigation was successful, false otherwise
     */
    private boolean navigateInRecyclerView(androidx.recyclerview.widget.RecyclerView recyclerView, int direction) {
        if (recyclerView == null || recyclerView.getAdapter() == null) {
            return false;
        }

        androidx.recyclerview.widget.RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager == null) {
            return false;
        }

        // Get currently focused item position
        View focusedView = recyclerView.getFocusedChild();
        int currentPosition = 0;

        if (focusedView != null) {
            currentPosition = recyclerView.getChildAdapterPosition(focusedView);
        } else {
            // If no item is focused, start from the first visible item
            if (layoutManager instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                currentPosition = ((androidx.recyclerview.widget.LinearLayoutManager) layoutManager).findFirstVisibleItemPosition();
            } else if (layoutManager instanceof androidx.recyclerview.widget.GridLayoutManager) {
                currentPosition = ((androidx.recyclerview.widget.GridLayoutManager) layoutManager).findFirstVisibleItemPosition();
            }
        }

        // Calculate new position
        int newPosition = currentPosition + direction;
        int itemCount = recyclerView.getAdapter().getItemCount();

        // Ensure position is within bounds
        if (newPosition < 0) {
            newPosition = 0;
        } else if (newPosition >= itemCount) {
            newPosition = itemCount - 1;
        }

        // Only navigate if position actually changed
        if (newPosition != currentPosition && newPosition >= 0 && newPosition < itemCount) {
            // Scroll to the new position and focus it
            final int finalNewPosition = newPosition;
            recyclerView.scrollToPosition(finalNewPosition);
            recyclerView.post(() -> {
                androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(finalNewPosition);
                if (viewHolder != null && viewHolder.itemView != null) {
                    viewHolder.itemView.requestFocus();
                }
            });
            return true;
        }

        return false;
    }
}
