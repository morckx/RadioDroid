package net.programmierecke.radiodroid2;

import android.util.Log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.Vector;

/**
 * Created by segler on 15.02.18.
 */

public class RadioBrowserServerManager {
    static String currentServer = null;
    static String[] serverList = null;

    /**
     * Test if a server is reachable
     * @param serverName the server to test
     * @return true if server is reachable, false otherwise
     */
    private static boolean isServerAvailable(String serverName) {
        try {
            URL url = new URL("https://" + serverName + "/");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000); // 3 seconds timeout
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET"); // Just get headers, not content
            int responseCode = connection.getResponseCode();
            return (200 <= responseCode && responseCode < 300);
        } catch (IOException e) {
            Log.w("DNS", "Server " + serverName + " is not available: " + e.getMessage());
            return false;
        }
    }

    /**
     * Blocking: do dns request do get a list of all available servers
     */
    private static String[] doDnsServerListing() {
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
                    if (isServerAvailable(name)) {
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
            if (isServerAvailable("all.api.radio-browser.info")) {
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
     */
    public static String[] getServerList(boolean forceRefresh){
        if (serverList == null || serverList.length == 0 || forceRefresh){
            serverList = doDnsServerListing();
        }
        return serverList;
    }

    /**
     * Blocking: return current selected server. Select one, if there is no current server.
     */
    public static String getCurrentServer() {
        if (currentServer == null){
            String[] serverList = getServerList(false);
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
