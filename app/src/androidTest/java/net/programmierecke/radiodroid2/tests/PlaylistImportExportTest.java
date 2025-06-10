package net.programmierecke.radiodroid2.tests;

import static net.programmierecke.radiodroid2.tests.utils.TestUtils.generateFakeRadioStation;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import net.programmierecke.radiodroid2.StationSaveManager;
import net.programmierecke.radiodroid2.station.DataRadioStation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PlaylistImportExportTest {
    private StationSaveManager manager;
    private Context context;
    private final String testFileName = "test_playlist.m3u";
    private String testFilePath;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        manager = new StationSaveManager(context);
        manager.clear();

        // Use app-specific storage for tests to avoid permission issues
        testFilePath = context.getExternalFilesDir(null).getAbsolutePath();
    }

    @After
    public void tearDown() {
        manager.clear();
        File file = new File(testFilePath, testFileName);
        if (file.exists()) file.delete();
    }

    @Test
    public void testExportImportPlaylist() {
        // Add a test station
        DataRadioStation station;
        station = generateFakeRadioStation(1); // Use a utility method to generate a fake station
        manager.add(station);
        assertEquals(1, manager.size());

        // Export to m3u
        boolean saveResult = manager.SaveM3UInternal(testFilePath, testFileName);
        assertTrue("Saving playlist failed", saveResult);

        File file = new File(testFilePath, testFileName);
        assertTrue("File doesn't exist after saving", file.exists());
        assertTrue("File is not readable", file.canRead());

        // Clear and import
        manager.clear();
        assertEquals(0, manager.size());
        List<DataRadioStation> imported = manager.LoadM3UInternal(testFilePath, testFileName);
        assertNotNull("Imported playlist is null", imported);
        assertEquals("Wrong number of imported stations", 1, imported.size());
    }
}
