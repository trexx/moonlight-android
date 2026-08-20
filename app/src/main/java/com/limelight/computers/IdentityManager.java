package com.limelight.computers;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Locale;
import java.util.Random;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import android.content.Context;

/**
 * The client ID sent to hosts, and the persistent per-install ID it can be set to.
 *
 * <p>By default every Moonlight client reports {@link #SHARED_UNIQUE_ID}, so that any of them can
 * quit a session another started. The "send a unique client ID" preference swaps that for this
 * install's own ID, which lets a host tell clients apart for its own session management at the
 * cost of that shared control.
 *
 * <p>The per-install ID is generated and stored whichever way the preference is set, so enabling
 * it later produces the same stable value rather than a fresh one. While it <em>is</em> enabled, a
 * host keys its records on it, and regenerating it would look like a new client.
 */
public class IdentityManager {
    private static final String UNIQUE_ID_FILE_NAME = "uniqueid";
    private static final int UID_SIZE_IN_BYTES = 8;

    /**
     * The ID every Moonlight client reports unless told otherwise.
     *
     * <p>Deliberately not unique: hosts that use it for session management then treat all
     * Moonlight clients as one, which is what allows a session started on one device to be quit
     * from another.
     */
    public static final String SHARED_UNIQUE_ID = "0123456789ABCDEF";

    private final Context context;
    private String uniqueId;

    /** Loads the stored client ID, generating one on first run. */
    public IdentityManager(Context c) {
        context = c.getApplicationContext();

        uniqueId = loadUniqueId(c);
        if (uniqueId == null) {
            uniqueId = generateNewUniqueId(c);
        }

        LimeLog.info("UID is now: "+uniqueId);
    }

    /**
     * @return the ID to report to hosts: this install's own if the user has asked for a unique
     *         client ID, otherwise {@link #SHARED_UNIQUE_ID}
     */
    public String getUniqueId() {
        // Read per call rather than cached at construction. This object is created once in
        // ComputerManagerService.onCreate() and lives as long as the service, so a cached value
        // would leave the preference apparently doing nothing until the process restarted.
        // SharedPreferences is an in-memory map after its first load, and every caller of this is
        // about to make an HTTP request.
        return PreferenceConfiguration.sendRealClientId(context) ? uniqueId : SHARED_UNIQUE_ID;
    }

    /** @return this install's own ID, whether or not it is currently being sent to hosts */
    public String getPersistentUniqueId() {
        return uniqueId;
    }

    private static String loadUniqueId(Context c) {
        // 2 Hex digits per byte
        char[] uid = new char[UID_SIZE_IN_BYTES * 2];
        LimeLog.info("Reading UID from disk");
        try (final InputStreamReader reader =
                     new InputStreamReader(c.openFileInput(UNIQUE_ID_FILE_NAME))
        ) {
            if (reader.read(uid) != UID_SIZE_IN_BYTES * 2) {
                LimeLog.severe("UID file data is truncated");
                return null;
            }
            return new String(uid);
        } catch (FileNotFoundException e) {
            LimeLog.info("No UID file found");
            return null;
        } catch (IOException e) {
            LimeLog.severe("Error while reading UID file");
            e.printStackTrace();
            return null;
        }
    }

    private static String generateNewUniqueId(Context c) {
        // Generate a new UID hex string
        LimeLog.info("Generating new UID");
        String uidStr = String.format((Locale)null, "%016x", new Random().nextLong());

        try (final OutputStreamWriter writer =
                     new OutputStreamWriter(c.openFileOutput(UNIQUE_ID_FILE_NAME, 0))
        ) {
            writer.write(uidStr);
            LimeLog.info("UID written to disk");
        } catch (IOException e) {
            LimeLog.severe("Error while writing UID file");
            e.printStackTrace();
        }

        // We can return a UID even if I/O fails
        return uidStr;
    }
}
