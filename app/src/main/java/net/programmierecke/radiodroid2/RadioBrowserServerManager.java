package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.Vector;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by segler on 15.02.18.
 */

public class RadioBrowserServerManager {
    static String currentServer = null;
    static String[] serverList = null;

    /**
     * Test if a server is reachable
     * @param serverName the server to test
     * @param httpClient the configured OkHttpClient to use for SSL/TLS handling
     * @param context the application context to access preferences
     * @return true if server is reachable, false otherwise
     */
    private static boolean isServerAvailable(String serverName, OkHttpClient httpClient, Context context) {
        // Check if Radio Browser server checking is disabled in preferences
        if (context != null) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            boolean checkRadioBrowserServer = sharedPreferences.getBoolean("settings_check_radio_browser_server", false);

            if (!checkRadioBrowserServer) {
                Log.i("DNS", "Radio Browser server checking disabled in preferences, skipping server availability check for " + serverName);
                return true;
            }
        } else {
            Log.w("DNS", "Context is null, skipping server availability check for " + serverName);
            // If context is null, we cannot access preferences, so we skip the check
            return true;
        }
        
        // On API level 24 and below, always return true to avoid potential compatibility issues
        if (android.os.Build.VERSION.SDK_INT <= 24) {
            Log.i("DNS", "API level <= 24: Skipping server availability check for " + serverName);
            return true;
        }

        try {
            Request request = new Request.Builder()
                    .url("https://" + serverName + "/")
                    .get() // Use HEAD request to avoid downloading content
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (IOException e) {
            Log.w("DNS", "Server " + serverName + " is not available: " + e.getMessage());
            return false;
        }
    }

    /**
     * Blocking: do dns request do get a list of all available servers
     * @param httpClient the configured OkHttpClient to use for SSL/TLS handling
     * @param context the application context to access preferences
     */
    private static String[] doDnsServerListing(OkHttpClient httpClient, Context context) {
        Log.d("DNS", "doDnsServerListing()");
        Vector<String> listResult = new Vector<String>();
        try {
            // add all round robin servers one by one to select them separately
            InetAddress[] list = InetAddress.getAllByName("all.api.radio-browser.info");
            for (InetAddress item : list) {
                // do not use original variable, it could fall back to "all.api.radio-browser.info"
                String currentHostAddress = item.getHostAddress();
                InetAddress new_item = InetAddress.getByName(currentHostAddress);
                Log.i("DNS", "Found: " + new_item + " -> " + new_item.getCanonicalHostName());
                String name = item.getCanonicalHostName();
                if (!name.equals("all.api.radio-browser.info") && !name.equals(currentHostAddress)) {
                    // Test if server is available before adding it
                    if (isServerAvailable(name, httpClient, context)) {
                        Log.i("DNS", "Added entry: '" + name + "'");
                        listResult.add(name);
                    } else {
                        Log.i("DNS", "Skipping unavailable server: '" + name + "'");
                    }
                }
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        if (listResult.isEmpty()){
            // should we inform people that their internet provider is not able to do reverse lookups? (= is shit)
            Log.w("DNS", "Fallback to all.api.radio-browser.info because dns call did not work.");
            // Check if fallback server is available
            if (isServerAvailable("all.api.radio-browser.info", httpClient, context)) {
                listResult.add("all.api.radio-browser.info");
            } else {
                Log.e("DNS", "Fallback server is also unavailable!");
            }
        }
        Log.d("DNS", "doDnsServerListing() Found servers: " + listResult.size());
        return listResult.toArray(new String[0]);
    }

    /**
     * Blocking: return current cached server list. Generate list if still null.
     * @param forceRefresh whether to force refresh the server list
     * @param httpClient the configured OkHttpClient to use for SSL/TLS handling
     * @param context the application context to access preferences
     */
    public static String[] getServerList(boolean forceRefresh, OkHttpClient httpClient, Context context){
        if (serverList == null || serverList.length == 0 || forceRefresh){
            serverList = doDnsServerListing(httpClient, context);
        }
        return serverList;
    }

    /**
     * Blocking: return current cached server list. Generate list if still null.
     * @deprecated Use getServerList(boolean, OkHttpClient, Context) instead for proper SSL/TLS handling
     */
    @Deprecated
    public static String[] getServerList(boolean forceRefresh){
        // Fallback to HttpClient.getInstance() for backward compatibility
        return getServerList(forceRefresh, HttpClient.getInstance(), null);
    }
    
    /**
     * Blocking: return current cached server list. Generate list if still null.
     * @deprecated Use getServerList(boolean, OkHttpClient, Context) instead for proper SSL/TLS handling and preference support
     */
    @Deprecated
    public static String[] getServerList(boolean forceRefresh, OkHttpClient httpClient){
        // Fallback to null context for backward compatibility
        return getServerList(forceRefresh, httpClient, null);
    }

    /**
     * Blocking: return current selected server. Select one, if there is no current server.
     * @param httpClient the configured OkHttpClient to use for SSL/TLS handling
     * @param context the application context to access preferences
     */
    public static String getCurrentServer(OkHttpClient httpClient, Context context) {
        if (currentServer == null){
            String[] serverList = getServerList(false, httpClient, context);
            if (serverList.length > 0){
                Random rand = new Random();
                currentServer = serverList[rand.nextInt(serverList.length)];
                Log.d("SRV", "Selected new default server: " + currentServer);
            }else{
                Log.e("SRV", "no servers found");
            }
        }
        return currentServer;
    }

    /**
     * Blocking: return current selected server. Select one, if there is no current server.
     * @deprecated Use getCurrentServer(OkHttpClient, Context) instead for proper SSL/TLS handling and preference support
     */
    @Deprecated
    public static String getCurrentServer() {
        // Fallback to HttpClient.getInstance() for backward compatibility
        return getCurrentServer(HttpClient.getInstance(), null);
    }
    
    /**
     * Blocking: return current selected server. Select one, if there is no current server.
     * @deprecated Use getCurrentServer(OkHttpClient, Context) instead for proper SSL/TLS handling and preference support
     */
    @Deprecated
    public static String getCurrentServer(OkHttpClient httpClient) {
        // Fallback to null context for backward compatibility
        return getCurrentServer(httpClient, null);
    }

    /**
     * Set new server as current
     */
    public static void setCurrentServer(String newServer){
        currentServer = newServer;
    }

    /**
     * Construct full url from server and path
     */
    public static String constructEndpoint(String server, String path){
        return "https://" + server + "/" + path;
    }
}
