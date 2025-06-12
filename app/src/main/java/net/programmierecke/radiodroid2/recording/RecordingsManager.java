package net.programmierecke.radiodroid2.recording;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Observable;

/* TODO: Actually have info about recording by storing them in the database and matching with files on disk.
 */
public class RecordingsManager {
    private final static String TAG = "Recordings";
    private final DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final DateFormat timeFormatter = new SimpleDateFormat("HH-mm", Locale.US);
    private final Context context;

    private class RecordingsObservable extends Observable {
        @Override
        public synchronized boolean hasChanged() {
            return true;
        }
    }

    private final Observable savedRecordingsObservable = new RecordingsObservable();

    private class RunningRecordableListener implements RecordableListener {
        private final RunningRecordingInfo runningRecordingInfo;
        private boolean ended;

        private RunningRecordableListener(@NonNull RunningRecordingInfo runningRecordingInfo) {
            this.runningRecordingInfo = runningRecordingInfo;
        }

        @Override
        public void onBytesAvailable(byte[] buffer, int offset, int length) {
            try {
                runningRecordingInfo.getOutputStream().write(buffer, offset, length);
                runningRecordingInfo.setBytesWritten(runningRecordingInfo.getBytesWritten() + length);
            } catch (IOException e) {
                e.printStackTrace();
                runningRecordingInfo.getRecordable().stopRecording();
            }
        }

        @Override
        public void onRecordingEnded() {
            if (ended) {
                return;
            }

            ended = true;

            try {
                runningRecordingInfo.getOutputStream().close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            RecordingsManager.this.stopRecording(runningRecordingInfo.getRecordable());
        }
    }

    private final Map<Recordable, RunningRecordingInfo> runningRecordings = new HashMap<>();
    private final ArrayList<DataRecording> savedRecordings = new ArrayList<>();
    
    public RecordingsManager(Context context) {
        this.context = context;
    }

    public void record(@NonNull Context context, @NonNull Recordable recordable) {
        if (!recordable.canRecord()) {
            return;
        }

        if (!runningRecordings.containsKey(recordable)) {
            RunningRecordingInfo info = new RunningRecordingInfo();

            info.setRecordable(recordable);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

            final String fileNameFormat = prefs.getString("record_name_formatting", context.getString(R.string.settings_record_name_formatting_default));

            final Map<String, String> formattingArgs = new HashMap<>(recordable.getRecordNameFormattingArgs());

            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());

            final Date currentTime = calendar.getTime();

            String dateStr = dateFormatter.format(currentTime);
            String timeStr = timeFormatter.format(currentTime);

            formattingArgs.put("date", dateStr);
            formattingArgs.put("time", timeStr);

            final int recordNum = prefs.getInt("record_num", 1);
            formattingArgs.put("index", Integer.toString(recordNum));

            final String recordTitle = Utils.formatStringWithNamedArgs(fileNameFormat, formattingArgs);

            info.setTitle(recordTitle);
            info.setFileName(String.format("%s.%s", recordTitle, recordable.getExtension()));

            //TODO: check available disk space here

            OutputStream outputStream;
            try {
                outputStream = createOutputStream(info, recordable.getExtension());
                info.setOutputStream(outputStream);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create output stream for recording: " + info.getFileName(), e);
                return;
            }

            recordable.startRecording(new RunningRecordableListener(info));

            runningRecordings.put(recordable, info);

            prefs.edit().putInt("record_num", recordNum + 1).apply();
        }
    }

    public void stopRecording(@NonNull Recordable recordable) {
        recordable.stopRecording();

        RunningRecordingInfo info = runningRecordings.remove(recordable);
        
        // Finalize MediaStore entry if using modern storage API
        if (info != null && info.getMediaStoreUri() != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            finalizeMediaStoreEntry(info);
        }

        updateRecordingsList();
    }

    public RunningRecordingInfo getRecordingInfo(Recordable recordable) {
        return runningRecordings.get(recordable);
    }

    public Map<Recordable, RunningRecordingInfo> getRunningRecordings() {
        return Collections.unmodifiableMap(runningRecordings);
    }

    public List<DataRecording> getSavedRecordings() {
        return new ArrayList<>(savedRecordings);
    }

    public Observable getSavedRecordingsObservable() {
        return savedRecordingsObservable;
    }

    /**
     * Creates an OutputStream for recording files.
     * Uses MediaStore API for Android 10+ and legacy file API for older versions.
     */
    private OutputStream createOutputStream(RunningRecordingInfo info, String extension) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore API for Android 10+
            return createOutputStreamWithMediaStore(info, extension);
        } else {
            // Use legacy file API for older Android versions
            return createOutputStreamLegacy(info.getFileName());
        }
    }

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    private OutputStream createOutputStreamWithMediaStore(RunningRecordingInfo info, String extension) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Audio.Media.DISPLAY_NAME, info.getFileName());
        contentValues.put(MediaStore.Audio.Media.MIME_TYPE, getMimeTypeForExtension(extension));
        contentValues.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Recordings");
        contentValues.put(MediaStore.Audio.Media.IS_PENDING, 1); // Mark as pending during recording
        
        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues);
        if (uri == null) {
            throw new IOException("Failed to create MediaStore entry for recording");
        }
        
        // Store the URI so we can finalize it later
        info.setMediaStoreUri(uri);
        
        return resolver.openOutputStream(uri);
    }
    
    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    private void finalizeMediaStoreEntry(RunningRecordingInfo info) {
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0); // Mark as completed
            
            int updated = resolver.update(info.getMediaStoreUri(), contentValues, null, null);
            if (updated > 0) {
                Log.d(TAG, "Successfully finalized MediaStore entry for: " + info.getFileName());
                
                // Schedule a delayed update to ensure the MediaStore change is visible
                new android.os.Handler().postDelayed(this::updateRecordingsList, 1200);
            } else {
                Log.w(TAG, "Failed to finalize MediaStore entry for: " + info.getFileName());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finalizing MediaStore entry for: " + info.getFileName(), e);
        }
    }
    
    private OutputStream createOutputStreamLegacy(String fileName) throws IOException {
        String pathRecordings = getRecordDirLegacy();
        String filePath = pathRecordings + "/" + fileName;
        return new FileOutputStream(filePath);
    }
    
    private String getMimeTypeForExtension(String extension) {
        switch (extension.toLowerCase()) {
            case "mp3":
                return "audio/mpeg";
            case "aac":
                return "audio/aac";
            case "m4a":
                return "audio/mp4";
            case "wav":
                return "audio/wav";
            default:
                return "audio/mpeg"; // Default to mp3
        }
    }

    public static String getRecordDir() {
        return getRecordDirLegacy();
    }
    
    private static String getRecordDirLegacy() {
        String pathRecordings = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) + "/Recordings";
        File folder = new File(pathRecordings);
        if (!folder.exists()) {
            if (!folder.mkdirs()) {
                Log.e(TAG, "could not create dir:" + pathRecordings);
            }
        }
        return pathRecordings;
    }

    public void updateRecordingsList() {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Updating recordings list");
        }

        savedRecordings.clear();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore API for Android 10+
            updateRecordingsListFromMediaStore();
        } else {
            // Use legacy file system for older Android versions
            updateRecordingsListFromFiles();
        }

        Collections.sort(savedRecordings, (o1, o2) -> Long.compare(o2.Time.getTime(), o1.Time.getTime()));
        savedRecordingsObservable.notifyObservers();
    }
    
    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.Q)
    private void updateRecordingsListFromMediaStore() {
        try {
            ContentResolver resolver = context.getContentResolver();
            
            String[] projection = {
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.RELATIVE_PATH
            };
            
            String selection = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
            String[] selectionArgs = {"%" + Environment.DIRECTORY_MUSIC + "/Recordings%"};
            
            String sortOrder = MediaStore.Audio.Media.DATE_MODIFIED + " DESC";
            
            try (android.database.Cursor cursor = resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder)) {
                
                if (cursor != null) {
                    int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                    int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);
                    
                    while (cursor.moveToNext()) {
                        String fileName = cursor.getString(nameColumn);
                        long dateModified = cursor.getLong(dateColumn) * 1000; // Convert to milliseconds
                        
                        DataRecording dr = new DataRecording();
                        dr.Name = fileName;
                        dr.Time = new Date(dateModified);
                        savedRecordings.add(dr);
                        
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Found MediaStore recording: " + fileName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaStore for recordings", e);
            // Fallback to file system method
            updateRecordingsListFromFiles();
        }
    }
    
    private void updateRecordingsListFromFiles() {
        String path = getRecordDir();
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Updating recordings from " + path);
        }

        File folder = new File(path);
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                DataRecording dr = new DataRecording();
                dr.Name = f.getName();
                dr.Time = new Date(f.lastModified());
                savedRecordings.add(dr);
            }
        } else {
            Log.e(TAG, "Could not enumerate files in recordings directory");
        }
    }
    
    /**
     * Checks if the app has permission to record audio to storage.
     * @return true if recording is allowed, false otherwise
     */
    public boolean hasRecordingPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires READ_MEDIA_AUDIO permission
            return Utils.hasPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12 can use MediaStore without explicit permissions for app-created content
            return true;
        } else {
            // Android 9 and below require WRITE_EXTERNAL_STORAGE
            return Utils.hasPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }
    
    /**
     * Gets the permissions needed for recording based on Android version.
     * @return array of permission strings needed
     */
    public String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{android.Manifest.permission.READ_MEDIA_AUDIO};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{}; // No explicit permissions needed for MediaStore
        } else {
            return new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
    }
}
