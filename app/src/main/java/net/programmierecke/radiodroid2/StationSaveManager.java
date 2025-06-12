package net.programmierecke.radiodroid2;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Observable;
import java.util.Vector;

import info.debatty.java.stringsimilarity.Cosine;
import okhttp3.OkHttpClient;

public class StationSaveManager extends Observable {
    protected interface StationStatusListener {
        void onStationStatusChanged(DataRadioStation station, boolean favourite);
    }

    Context context;
    List<DataRadioStation> listStations = new ArrayList<DataRadioStation>();

    protected StationStatusListener stationStatusListener;

    public StationSaveManager(Context ctx) {
        this.context = ctx;
        Load();
    }

    protected String getSaveId() {
        return "default";
    }

    protected void setStationStatusListener(StationStatusListener stationStatusListener) {
        this.stationStatusListener = stationStatusListener;
    }

    public void add(DataRadioStation station) {
        if (station.queue == null)
            station.queue = this;
        listStations.add(station);
        Save();

        notifyObservers();

        if (stationStatusListener != null) {
            stationStatusListener.onStationStatusChanged(station, true);
        }
    }

    public void addMultiple(List<DataRadioStation> stations) {
        for (DataRadioStation station_new: stations){
            listStations.add(station_new);
        }
        Save();

        notifyObservers();
    }

    public void replaceList(List<DataRadioStation> stations_new) {
        for (DataRadioStation station_new: stations_new) {
            for (int i = 0; i < listStations.size(); i++) {
                if (listStations.get(i).StationUuid.equals(station_new.StationUuid)){
                    listStations.set(i, station_new);
                    break;
                }
            }
        }
        Save();

        notifyObservers();
    }

    public void addFront(DataRadioStation station) {
        if (station.queue == null)
            station.queue = this;
        listStations.add(0, station);
        Save();

        notifyObservers();

        if (stationStatusListener != null) {
            stationStatusListener.onStationStatusChanged(station, true);
        }
    }

    public void addAll(List<DataRadioStation> stations) {
        if (stations == null)
            return;
        for (DataRadioStation station : stations) {
            station.queue = this;
        }
        listStations.addAll(stations);
    }
    
    public DataRadioStation getLast() {
        if (!listStations.isEmpty()) {
            return listStations.get(listStations.size() - 1);
        }

        return null;
    }

    public DataRadioStation getFirst() {
        if (!listStations.isEmpty()) {
            return listStations.get(0);
        }

        return null;
    }

    public DataRadioStation getById(String id) {
        for (DataRadioStation station : listStations) {
            if (id.equals(station.StationUuid)) {
                return station;
            }
        }
        return null;
    }

    public DataRadioStation getNextById(String id) {
        if (listStations.isEmpty())
            return null;

        for (int i = 0; i < listStations.size() - 1; i++) {
            if (listStations.get(i).StationUuid.equals(id)) {
                return listStations.get(i + 1);
            }
        }
        return listStations.get(0);
    }

    public DataRadioStation getPreviousById(String id) {
        if (listStations.isEmpty())
            return null;

        for (int i = 1; i < listStations.size(); i++) {
            if (listStations.get(i).StationUuid.equals(id)) {
                return listStations.get(i - 1);
            }
        }
        return listStations.get(listStations.size() - 1);
    }

    public void moveWithoutNotify(int fromPos, int toPos) {
        Collections.rotate(listStations.subList(Math.min(fromPos, toPos), Math.max(fromPos, toPos) + 1), Integer.signum(fromPos - toPos));
    }

    public void move(int fromPos, int toPos) {
        moveWithoutNotify(fromPos, toPos);
        notifyObservers();
    }

    public @Nullable
    DataRadioStation getBestNameMatch(String query) {
        DataRadioStation bestStation = null;
        query = query.toUpperCase();
        double smallesDistance = Double.MAX_VALUE;

        Cosine distMeasure = new Cosine(); // must be in the loop for some measures (e.g. Sift4)
        for (DataRadioStation station : listStations) {
            double distance = distMeasure.distance(station.Name.toUpperCase(), query);
            if (distance < smallesDistance) {
                bestStation = station;
                smallesDistance = distance;
            }
        }

        return bestStation;
    }

    public int remove(String id) {
        for (int i = 0; i < listStations.size(); i++) {
            DataRadioStation station = listStations.get(i);
            if (station.StationUuid.equals(id)) {
                listStations.remove(i);
                Save();
                notifyObservers();

                if (stationStatusListener != null) {
                    stationStatusListener.onStationStatusChanged(station, false);
                }

                return i;
            }
        }

        return -1;
    }

    public void restore(DataRadioStation station, int pos) {
        station.queue = this;
        listStations.add(pos, station);
        Save();

        notifyObservers();

        if (stationStatusListener != null) {
            stationStatusListener.onStationStatusChanged(station, false);
        }
    }

    public void clear() {
        List<DataRadioStation> oldStation = listStations;
        listStations = new ArrayList<>();
        Save();

        notifyObservers();

        if (stationStatusListener != null) {
            for (DataRadioStation station : oldStation) {
                stationStatusListener.onStationStatusChanged(station, false);
            }
        }
    }

    @Override
    public boolean hasChanged() {
        return true;
    }

    public int size() {
        return listStations.size();
    }

    public boolean isEmpty() {
        return listStations.size() == 0;
    }

    public boolean has(String id) {
        DataRadioStation station = getById(id);
        return station != null;
    }

    private boolean hasInvalidUuids() {
        for (DataRadioStation station : listStations) {
            if (!station.hasValidUuid()) {
                return true;
            }
        }

        return false;
    }

    public List<DataRadioStation> getList() {
        return Collections.unmodifiableList(listStations);
    }

    private void refreshStationsFromServer() {
        final RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        final OkHttpClient httpClient = radioDroidApp.getHttpClient();
        LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(ActivityMain.ACTION_SHOW_LOADING));

        new AsyncTask<Void, Void, ArrayList<DataRadioStation>>() {
            private ArrayList<DataRadioStation> savedStations;

            @Override
            protected void onPreExecute() {
                savedStations = new ArrayList<>(listStations);
            }

            @Override
            protected ArrayList<DataRadioStation> doInBackground(Void... params) {
                ArrayList<DataRadioStation> stationsToRemove = new ArrayList<>();
                for (DataRadioStation station : savedStations) {
                    if (!station.refresh(httpClient, context) && !station.hasValidUuid() && station.RefreshRetryCount > DataRadioStation.MAX_REFRESH_RETRIES) {
                        stationsToRemove.add(station);
                    }
                }

                return stationsToRemove;
            }

            @Override
            protected void onPostExecute(ArrayList<DataRadioStation> stationsToRemove) {
                listStations.removeAll(stationsToRemove);

                Save();

                notifyObservers();

                LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(ActivityMain.ACTION_HIDE_LOADING));
                super.onPostExecute(stationsToRemove);
            }
        }.execute();
    }

    void Load() {
        listStations.clear();

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        String str = sharedPref.getString(getSaveId(), null);
        if (str != null) {
            List<DataRadioStation> arr = DataRadioStation.DecodeJson(str);
            for (DataRadioStation station : arr) {
                station.queue = this;
            }
            listStations.addAll(arr);
            if (hasInvalidUuids() && Utils.hasAnyConnection(context)) {
                refreshStationsFromServer();
            }
        } else {
            Log.w("SAVE", "Load() no stations to load");
        }
    }

    void Save() {
        JSONArray arr = new JSONArray();
        for (DataRadioStation station : listStations) {
            arr.put(station.toJson());
        }

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPref.edit();
        String str = arr.toString();
        if (BuildConfig.DEBUG) {
            Log.d("SAVE", "wrote: " + str);
        }
        editor.putString(getSaveId(), str);
        editor.commit();
    }

    public static String getSaveDir() {
        // For tests or modern Android versions, use app-specific storage
        if (BuildConfig.DEBUG || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Return null in this case to let the caller handle selecting an appropriate directory
            return null;
        } else {
            // Legacy approach for older Android versions in production
            String path = String.valueOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
            File folder = new File(path);
            if (!folder.exists()) {
                if (!folder.mkdirs()) {
                    Log.e("SAVE", "could not create dir:" + path);
                }
            }
            return path;
        }
    }

    public void SaveM3U(final String filePath, final String fileName) {
        Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_now, filePath, fileName), Toast.LENGTH_LONG);
        toast.show();

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                return SaveM3UInternal(filePath, fileName);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                if (result.booleanValue()) {
                    Log.i("SAVE", "OK");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_ok, filePath, fileName), Toast.LENGTH_LONG);
                    toast.show();
                } else {
                    Log.i("SAVE", "NOK");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_nok, filePath, fileName), Toast.LENGTH_LONG);
                    toast.show();
                }
                super.onPostExecute(result);
            }
        }.execute();
    }

    public void SaveM3USimple(final String filePath, final String fileName) {
        Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_now, filePath, fileName), Toast.LENGTH_LONG);
        toast.show();

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                return SaveM3UInternal(filePath, fileName);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                if (result.booleanValue()) {
                    Log.i("SAVE", "OK");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_ok, filePath, fileName), Toast.LENGTH_LONG);
                    toast.show();
                } else {
                    Log.i("SAVE", "NOK");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_nok, filePath, fileName), Toast.LENGTH_LONG);
                    toast.show();
                }
                super.onPostExecute(result);
            }
        }.execute();
    }

    public void LoadM3U(final String filePath, final String fileName) {
        Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_now, filePath, fileName), Toast.LENGTH_LONG);
        toast.show();

        new AsyncTask<Void, Void, List<DataRadioStation>>() {
            @Override
            protected List<DataRadioStation> doInBackground(Void... params) {
                return LoadM3UInternal(filePath, fileName);
            }

            @Override
            protected void onPostExecute(List<DataRadioStation> result) {
                if (result != null) {
                    Log.i("LOAD", "Loaded " + result.size() + "stations");
                    addMultiple(result);
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_ok, result.size(), filePath, fileName), Toast.LENGTH_LONG);
                    toast.show();
                } else {
                    Log.e("LOAD", "Load failed");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_nok, filePath, fileName), Toast.LENGTH_LONG);
                    toast.show();
                }

                notifyObservers();

                super.onPostExecute(result);
            }
        }.execute();
    }

    public void LoadM3USimple(final Reader reader, final String displayName) {
        // Use SAF-specific string resource for import notification
        Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_now_saf, displayName), Toast.LENGTH_LONG);
        toast.show();

        new AsyncTask<Void, Void, List<DataRadioStation>>() {
            @Override
            protected List<DataRadioStation> doInBackground(Void... params) {
                return LoadM3UReader(reader);
            }

            @Override
            protected void onPostExecute(List<DataRadioStation> result) {
                if (result != null) {
                    Log.i("LOAD", "Loaded " + result.size() + "stations");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_ok_nf, result.size()), Toast.LENGTH_LONG);
                    addMultiple(result);
                    toast.show();
                } else {
                    Log.e("LOAD", "Load failed");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_nok, displayName, ""), Toast.LENGTH_LONG);
                    toast.show();
                }

                notifyObservers();

                super.onPostExecute(result);
            }
        }.execute();
    }

    protected final String M3U_PREFIX = "#RADIOBROWSERUUID:";

    public boolean SaveM3UInternal(String filePath, String fileName) {
        try {
            // For tests, save to app-specific directory
            if (BuildConfig.DEBUG) {
                File privateFile = new File(context.getExternalFilesDir(null), fileName);
                BufferedWriter bw = new BufferedWriter(new FileWriter(privateFile, false));
                boolean result = SaveM3UWriter(bw);
                Log.d("SAVE", "Saved to app-specific directory: " + privateFile.getAbsolutePath());
                return result;
            }

            // For Android 10+ use MediaStore API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return saveM3UUsingMediaStore(fileName);
            }

            // Legacy approach for older Android versions
            File f = new File(filePath, fileName);
            BufferedWriter bw = new BufferedWriter(new FileWriter(f, false));
            boolean result = SaveM3UWriter(bw);
            MediaScannerConnection.scanFile(context, new String[]{f.getAbsolutePath()}, null, null);
            return result;
        } catch (Exception e) {
            Log.e("SAVE", "File write failed: " + e);
            return false;
        }
    }

    private boolean saveM3UUsingMediaStore(String fileName) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "audio/x-mpegurl");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri contentUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } else {
                // Fallback for older versions - this shouldn't be reached since we check Q earlier
                return false;
            }
            Uri fileUri = context.getContentResolver().insert(contentUri, values);

            if (fileUri != null) {
                try (OutputStream os = context.getContentResolver().openOutputStream(fileUri)) {
                    if (os != null) {
                        OutputStreamWriter writer = new OutputStreamWriter(os);
                        boolean result = SaveM3UWriter(writer);

                        // Mark the file as no longer pending
                        values.clear();
                        values.put(MediaStore.Downloads.IS_PENDING, 0);
                        context.getContentResolver().update(fileUri, values, null, null);

                        return result;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.e("SAVE", "MediaStore save failed: " + e);
            return false;
        }
    }

    public boolean SaveM3UWriter(Writer bw) {
        final RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        final OkHttpClient httpClient = radioDroidApp.getHttpClient();

        try {
            bw.write("#EXTM3U\n");
            for (DataRadioStation station : listStations) {
                bw.write(M3U_PREFIX + station.StationUuid + "\n");
                bw.write("#EXTINF:-1," + station.Name + "\n");
                bw.write(station.StreamUrl + "\n\n");
            }
            bw.flush();
            bw.close();

            return true;
        } catch (Exception e) {
            Log.e("Exception", "File write failed: " + e);
            return false;
        }
    }

    public List<DataRadioStation> LoadM3UInternal(String filePath, String fileName) {
        try {
            // Try app-specific directory first for tests
            if (BuildConfig.DEBUG) {
                File privateFile = new File(context.getExternalFilesDir(null), fileName);
                if (privateFile.exists() && privateFile.canRead()) {
                    Log.d("LOAD", "Loading from app-specific directory: " + privateFile.getAbsolutePath());
                    FileReader fr = new FileReader(privateFile);
                    return LoadM3UReader(fr);
                } else {
                    // Copy test file to app-specific directory for tests if we're in debug mode
                    File externalFile = new File(filePath, fileName);
                    if (externalFile.exists()) {
                        try {
                            // Copy the file to our app-specific directory
                            java.io.FileInputStream inStream = new java.io.FileInputStream(externalFile);
                            java.io.FileOutputStream outStream = new java.io.FileOutputStream(privateFile);
                            byte[] buffer = new byte[1024];
                            int length;
                            while ((length = inStream.read(buffer)) > 0) {
                                outStream.write(buffer, 0, length);
                            }
                            inStream.close();
                            outStream.close();
                            Log.d("LOAD", "Copied test file to app-specific directory: " + privateFile.getAbsolutePath());

                            // Now read from the copied file
                            FileReader fr = new FileReader(privateFile);
                            return LoadM3UReader(fr);
                        } catch (Exception e) {
                            Log.w("LOAD", "Failed to copy file: " + e.getMessage());
                        }
                    }
                }
            }

            File f = new File(filePath, fileName);

            // Check if file exists
            if (!f.exists()) {
                Log.e("LOAD", "File does not exist: " + f.getAbsolutePath());
                return null;
            }

            // For Android 10+ ensure we have proper access
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // Try to access through MediaStore for downloads on Android 10+
                    Uri contentUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL);
                    String selection = MediaStore.Downloads.DISPLAY_NAME + "=?";
                    String[] selectionArgs = new String[] { fileName };
                    Cursor cursor = context.getContentResolver().query(contentUri, null, selection, selectionArgs, null);

                    if (cursor != null && cursor.moveToFirst()) {
                        int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                        long id = cursor.getLong(idColumn);
                        Uri fileUri = ContentUris.withAppendedId(contentUri, id);

                        InputStreamReader reader = new InputStreamReader(context.getContentResolver().openInputStream(fileUri));
                        cursor.close();
                        return LoadM3UReader(reader);
                    }

                    if (cursor != null) {
                        cursor.close();
                    }
                } catch (Exception e) {
                    Log.w("LOAD", "MediaStore approach failed: " + e + ", falling back to direct file access");
                }
            }

            // Try direct file access as last resort
            try {
                FileReader fr = new FileReader(f);
                return LoadM3UReader(fr);
            } catch (Exception e) {
                Log.e("LOAD", "Direct file access failed: " + e);
                return null;
            }
        } catch (Exception e) {
            Log.e("LOAD", "File read failed: " + e);
            return null;
        }
    }

    List<DataRadioStation> LoadM3UReader(Reader reader) {
        try {
            String line;

            final RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
            final OkHttpClient httpClient = radioDroidApp.getHttpClient();
            ArrayList<String> listUuids = new ArrayList<String>();
            ArraySet<DataRadioStation> loadedItems = null;

            BufferedReader br = new BufferedReader(reader);
            while ((line = br.readLine()) != null) {
                Log.v("LOAD", "line: "+line);
                if (line.startsWith(M3U_PREFIX)) {
                    try {
                        String uuid = line.substring(M3U_PREFIX.length()).trim();
                        listUuids.add(uuid); // <-- FIX: actually collect UUIDs
                        DataRadioStation station = Utils.getStationByUuid(httpClient, context, uuid);
                        if (station != null) {
                            station.queue = this;
                        }
                    } catch (Exception e) {
                        Log.e("LOAD", e.toString());
                    }
                }
            }
            br.close();

            List<DataRadioStation> listStationsNew = Utils.getStationsByUuid(httpClient, context, listUuids);

            // sort list to have the same order as the initial save file
            List<DataRadioStation> listStationsSorted = new ArrayList<DataRadioStation>();
            for (String uuid: listUuids)
            {
                assert listStationsNew != null;
                for (DataRadioStation s: listStationsNew){
                    if (uuid.equals(s.StationUuid)){
                        listStationsSorted.add(s);
                        break;
                    }
                }
            }
            if (listStationsSorted.isEmpty()) {
                Log.w("LOAD", "No stations loaded from M3U file");
                return listStationsNew;
            }
            return listStationsSorted;
        } catch (Exception e) {
            Log.e("LOAD", "File read failed: " + e);
            return null;
        }
    }
}

