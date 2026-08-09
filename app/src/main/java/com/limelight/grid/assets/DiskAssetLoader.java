package com.limelight.grid.assets;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.ImageDecoder;

import com.limelight.LimeLog;
import com.limelight.utils.CacheHelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * On-disk box art cache, in the app's cache directory keyed by host UUID and app ID.
 *
 * <p>The middle cache tier, and the one the Android TV launcher reads through
 * {@link com.limelight.PosterContentProvider}. Decoding is done with a sample size, so a large
 * image on disk costs only the memory the grid cell needs.
 */
public class DiskAssetLoader {
    // 5 MB
    private static final long MAX_ASSET_SIZE = 5 * 1024 * 1024;

    // Standard box art is 300x400
    private static final int STANDARD_ASSET_WIDTH = 300;
    private static final int STANDARD_ASSET_HEIGHT = 400;

    private final boolean isLowRamDevice;
    private final File cacheDir;

    /** @param context supplies the cache directory the assets live in */
    public DiskAssetLoader(Context context) {
        this.cacheDir = context.getCacheDir();
        this.isLowRamDevice =
                ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).isLowRamDevice();
    }

    /** @return true if this asset is on disk, without decoding it */
    public boolean checkCacheExists(CachedAppAssetLoader.LoaderTuple tuple) {
        return CacheHelper.cacheFileExists(cacheDir, "boxart", tuple.computer.uuid, tuple.app.getAppId() + ".png");
    }

    /**
     * @param sampleSize downsampling factor, a power of two, applied while decoding
     * @return the decoded bitmap, or null if it isn't cached or the file is corrupt
     */
    public ScaledBitmap loadBitmapFromCache(CachedAppAssetLoader.LoaderTuple tuple, int sampleSize) {
        File file = getFile(tuple.computer.uuid, tuple.app.getAppId());

        // Don't bother with anything if it doesn't exist
        if (!file.exists()) {
            return null;
        }

        // Make sure the cached asset doesn't exceed the maximum size
        if (file.length() > MAX_ASSET_SIZE) {
            LimeLog.warning("Removing cached tuple exceeding size threshold: "+tuple);
            file.delete();
            return null;
        }

        final ScaledBitmap scaledBitmap = new ScaledBitmap();
        try {
            scaledBitmap.bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), new ImageDecoder.OnHeaderDecodedListener() {
                @Override
                public void onHeaderDecoded(ImageDecoder imageDecoder, ImageDecoder.ImageInfo imageInfo, ImageDecoder.Source source) {
                    scaledBitmap.originalWidth = imageInfo.getSize().getWidth();
                    scaledBitmap.originalHeight = imageInfo.getSize().getHeight();

                    imageDecoder.setTargetSize(STANDARD_ASSET_WIDTH, STANDARD_ASSET_HEIGHT);
                    if (isLowRamDevice) {
                        imageDecoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                    }
                }
            });
            return scaledBitmap;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /** @return the cache file for this asset, which may not exist */
    public File getFile(String computerUuid, int appId) {
        return CacheHelper.openPath(false, cacheDir, "boxart", computerUuid, appId + ".png");
    }

    /** Deletes all cached art for a host, when that host is removed. */
    public void deleteAssetsForComputer(String computerUuid) {
        File dir = CacheHelper.openPath(false, cacheDir, "boxart", computerUuid);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    /**
     * Writes a freshly downloaded asset to the cache, via a temporary file so that an interrupted
     * write cannot leave a truncated image behind for the next load to find.
     */
    public void populateCacheWithStream(CachedAppAssetLoader.LoaderTuple tuple, InputStream input) {
        boolean success = false;
        try (final OutputStream out = CacheHelper.openCacheFileForOutput(
                cacheDir, "boxart", tuple.computer.uuid, tuple.app.getAppId() + ".png")
        ) {
            CacheHelper.writeInputStreamToOutputStream(input, out, MAX_ASSET_SIZE);
            success = true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (!success) {
                LimeLog.warning("Unable to populate cache with tuple: "+tuple);
                CacheHelper.deleteCacheFile(cacheDir, "boxart", tuple.computer.uuid, tuple.app.getAppId() + ".png");
            }
        }
    }
}
