package net.programmierecke.radiodroid2;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import de.sfuhrm.radiobrowser4j.FieldName;
import de.sfuhrm.radiobrowser4j.ListParameter;
import de.sfuhrm.radiobrowser4j.RadioBrowser;
import de.sfuhrm.radiobrowser4j.RadioBrowserException;
import de.sfuhrm.radiobrowser4j.SearchMode;
import de.sfuhrm.radiobrowser4j.Station;
import info.debatty.java.stringsimilarity.Cosine;

public class SearchResultsManager extends StationSaveManager {
    private static final double DEFAULT_DISTANCE_THRESHOLD = 0.5;
    private static final String TAG = "SearchResultsManager";
    private static Cosine distMeasure;

    public SearchResultsManager(Context ctx) {
        super(ctx);
        distMeasure = new Cosine();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public void searchServer(String query) {
        RadioBrowser browser = new RadioBrowser(2000, "RadioDroid2/" + BuildConfig.VERSION_NAME);
        browser.listStationsBy(SearchMode.BYNAME, query, ListParameter.create().order(FieldName.CLICKCOUNT))
                .filter(s -> s.getLastcheckok() == 1)
                .limit(10)
                .forEach(s -> addUniqe(DataRadioStation.DataRadioStationFromStation(s)));
        sortByDistanceFromQuery();
    }

    public static class AsyncStationSearchTask extends AsyncTask<Object, Void, DataRadioStation> {
        private OnStationSearchCompleted listener;
        private String query;
        private SearchResultsManager searchResultsManager;

        public AsyncStationSearchTask(SearchResultsManager searchResultsManager, String query, OnStationSearchCompleted listener) {
            this.searchResultsManager = searchResultsManager;
            this.listener = listener;
            this.query = query;
        }

        @Override
        protected DataRadioStation doInBackground(Object[] objects) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    return searchResultsManager.getMatchesFromAllStationLists(query);
                } catch (RadioBrowserException rbe) {}
            }
            return null;
        }

        @Override
        protected void onPostExecute(DataRadioStation bestMatch) {
           listener.onStationSearchCompletetd(bestMatch);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public @Nullable
    DataRadioStation getMatchesFromAllStationLists(String query) {
        clear();
        RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        List<StationSaveManager> stationSaveManagerList = Arrays.asList( radioDroidApp.getFavouriteManager(),
                radioDroidApp.getHistoryManager(),
                radioDroidApp.getFallbackStationsManager());

        query = query.toUpperCase();
        double smallestDistance = Double.MAX_VALUE;

        for (StationSaveManager stationSaveManager: stationSaveManagerList) {
            double minListDistance = recalcDistancesFromQuery(stationSaveManager, query);
            if (minListDistance < smallestDistance) {
                smallestDistance = minListDistance;
            }
        }

        double threshold = Math.max(smallestDistance, DEFAULT_DISTANCE_THRESHOLD);
        for (StationSaveManager stationSaveManager: stationSaveManagerList) {
            listStations.addAll(stationSaveManager.getList().stream().filter(s -> s.distanceFromQuery <= threshold).collect(Collectors.toList()));
        }
        searchServer(query);
        sortByDistanceFromQuery();
        logSearchResult(query);
        return getFirst();
    }

    public interface OnStationSearchCompleted {
        void onStationSearchCompletetd(DataRadioStation bestMatchStation);
    }

    public void sortByDistanceFromQuery() {
        Collections.sort(listStations, (o1, o2) -> Double.compare(o2.distanceFromQuery, o1.distanceFromQuery));
    }

    public @Nullable
    static double recalcDistancesFromQuery(StationSaveManager stationSaveManager, String query) {
        query = query.toUpperCase();
        double smallesDistance = Double.MAX_VALUE;

        for (DataRadioStation station : stationSaveManager.listStations) {
            station.distanceFromQuery = stringDistance(station.Name.toUpperCase(), query);
            if (station.distanceFromQuery < smallesDistance) {
                smallesDistance = station.distanceFromQuery;
            }
        }
        return smallesDistance;
    }

    static double stringDistance(String name, String query) {
        return distMeasure.distance(name.toUpperCase().replaceAll("\\s*\\[.*", ""), query);
    }

    void logSearchResult(String query) {
        Log.d(TAG, query);
        for (DataRadioStation station: listStations) {
            Log.d(TAG, "found: "+station.Name+ " "+station.distanceFromQuery);
        }
    }
}
