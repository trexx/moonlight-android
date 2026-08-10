package com.limelight.grid.assets;

import android.util.LruCache;

import com.limelight.LimeLog;

import java.lang.ref.SoftReference;
import java.util.HashMap;

/**
 * In-memory box art cache, sized as a fraction of the app's heap.
 *
 * <p>The first tier of the three the grid uses: memory, then disk
 * ({@link DiskAssetLoader}), then the host ({@link NetworkAssetLoader}).
 */
public class MemoryAssetLoader {
    private static final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
    private static final LruCache<String, ScaledBitmap> memoryCache = new LruCache<String, ScaledBitmap>(maxMemory / 16) {
        @Override
        protected int sizeOf(String key, ScaledBitmap bitmap) {
            // Sizeof returns kilobytes
            return bitmap.bitmap.getByteCount() / 1024;
        }

        @Override
        protected void entryRemoved(boolean evicted, String key, ScaledBitmap oldValue, ScaledBitmap newValue) {
            super.entryRemoved(evicted, key, oldValue, newValue);

            if (evicted) {
                // Keep a soft reference around to the bitmap as long as we can
                evictionCache.put(key, new SoftReference<>(oldValue));
            }
        }
    };
    private static final HashMap<String, SoftReference<ScaledBitmap>> evictionCache = new HashMap<>();

    private static String constructKey(CachedAppAssetLoader.LoaderTuple tuple) {
        return tuple.computer.uuid+"-"+tuple.app.getAppId();
    }

    /**
     * @return the cached bitmap if one is present and detailed enough for the requested size, or
     *         null. A cached bitmap decoded for a smaller cell is rejected rather than upscaled.
     */
    public ScaledBitmap loadBitmapFromCache(CachedAppAssetLoader.LoaderTuple tuple) {
        final String key = constructKey(tuple);

        ScaledBitmap bmp = memoryCache.get(key);
        if (bmp != null) {
            LimeLog.info("LRU cache hit for tuple: "+tuple);
            return bmp;
        }

        SoftReference<ScaledBitmap> bmpRef = evictionCache.get(key);
        if (bmpRef != null) {
            bmp = bmpRef.get();
            if (bmp != null) {
                LimeLog.info("Eviction cache hit for tuple: "+tuple);

                // Put this entry back into the LRU cache
                evictionCache.remove(key);
                memoryCache.put(key, bmp);

                return bmp;
            }
            else {
                // The data is gone, so remove the dangling SoftReference now
                evictionCache.remove(key);
            }
        }

        return null;
    }

    /** Stores a decoded bitmap for later reuse. */
    public void populateCache(CachedAppAssetLoader.LoaderTuple tuple, ScaledBitmap bitmap) {
        memoryCache.put(constructKey(tuple), bitmap);
    }

    /** Drops everything, e.g. under memory pressure. */
    public void clearCache() {
        // We must evict first because that will push all items into the eviction cache
        memoryCache.evictAll();
        evictionCache.clear();
    }
}
