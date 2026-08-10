package com.limelight.grid.assets;

import android.content.Context;

import com.limelight.LimeLog;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.utils.ServerHelper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Fetches box art from the host over HTTPS. The last resort of the three cache tiers, and the only
 * one that can be slow or fail.
 */
public class NetworkAssetLoader {
    private final Context context;
    private final String uniqueId;

    /** @param uniqueId this client's ID, which the host requires to serve box art */
    public NetworkAssetLoader(Context context, String uniqueId) {
        this.context = context;
        this.uniqueId = uniqueId;
    }

    /**
     * @return a stream of the app's box art, or null if the host doesn't have it. The caller owns
     *         the stream and must close it.
     */
    public InputStream getBitmapStream(CachedAppAssetLoader.LoaderTuple tuple) {
        InputStream in = null;
        try {
            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(tuple.computer),
                    tuple.computer.httpsPort, uniqueId, tuple.computer.serverCert,
                    PlatformBinding.getCryptoProvider(context));
            in = http.getBoxArt(tuple.app);
        } catch (IOException ignored) {}

        if (in != null) {
            LimeLog.info("Network asset load complete: " + tuple);
        }
        else {
            LimeLog.info("Network asset load failed: " + tuple);
        }

        return in;
    }
}
