package com.limelight;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.limelight.grid.assets.DiskAssetLoader;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Serves cached box art to the Android TV launcher.
 *
 * <p>The launcher renders channel programs in its own process and cannot read this app's private
 * files, so the artwork has to be exposed through a content provider. Read-only by design: every
 * mutating operation below is unsupported, and only the box art cache directory is reachable.
 */
public class PosterContentProvider extends ContentProvider {


    public static final String AUTHORITY = "poster." + BuildConfig.APPLICATION_ID;
    public static final String PNG_MIME_TYPE = "image/png";
    public static final int APP_ID_PATH_INDEX = 2;
    public static final int COMPUTER_UUID_PATH_INDEX = 1;
    private DiskAssetLoader mDiskAssetLoader;

    private static final UriMatcher sUriMatcher;
    private static final String BOXART_PATH = "boxart";
    private static final int BOXART_URI_ID = 1;

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(AUTHORITY, BOXART_PATH, BOXART_URI_ID);
    }

    /** {@inheritDoc} Serves box art only; every other path is rejected. */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int match = sUriMatcher.match(uri);
        if (match == BOXART_URI_ID) {
            return openBoxArtFile(uri, mode);
        }
        return openBoxArtFile(uri, mode);

    }

    /**
     * @return a read-only descriptor for the cached box art the URI names
     * @throws FileNotFoundException if the URI is malformed or that art isn't cached
     */
    public ParcelFileDescriptor openBoxArtFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new UnsupportedOperationException("This provider is only for read mode");
        }

        List<String> segments = uri.getPathSegments();
        if (segments.size() != 3) {
            throw new FileNotFoundException();
        }
        String appId = segments.get(APP_ID_PATH_INDEX);
        String uuid = segments.get(COMPUTER_UUID_PATH_INDEX);
        File file = mDiskAssetLoader.getFile(uuid, Integer.parseInt(appId));
        if (file.exists()) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        throw new FileNotFoundException();
    }

    /** {@inheritDoc} Unsupported: this provider is read-only. */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("This provider is only for read mode");
    }

    /** {@inheritDoc} @return the MIME type of the cached box art */
    @Override
    public String getType(Uri uri) {
        return PNG_MIME_TYPE;
    }

    /** {@inheritDoc} Unsupported: this provider is read-only. */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("This provider is only for read mode");
    }

    /** {@inheritDoc} Nothing to initialise; the cache directory is resolved per request. */
    @Override
    public boolean onCreate() {
        mDiskAssetLoader = new DiskAssetLoader(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("This provider doesn't support query");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("This provider is support read only");
    }


    /** @return the content URI the launcher should use to fetch one app's box art */
    public static Uri createBoxArtUri(String uuid, String appId) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(BOXART_PATH)
                .appendPath(uuid)
                .appendPath(appId)
                .build();
    }

}
