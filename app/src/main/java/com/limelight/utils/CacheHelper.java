package com.limelight.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;

/**
 * Path and stream helpers for the app's cache directory.
 *
 * <p>Callers pass a path as varargs components rather than building strings, so cache paths are
 * assembled the same way everywhere and can't accidentally escape the cache root.
 */
public class CacheHelper {
    /**
     * @param createPath create the intervening directories
     * @return the file at the given path components under {@code root}
     */
    public static File openPath(boolean createPath, File root, String... path) {
        File f = root;
        for (int i = 0; i < path.length; i++) {
            String component = path[i];

            if (i == path.length - 1) {
                // This is the file component so now we create parent directories
                if (createPath) {
                    f.mkdirs();
                }
            }

            f = new File(f, component);
        }
        return f;
    }

    /** @return true if the cache file existed and was deleted */
    public static boolean deleteCacheFile(File root, String... path) {
        return openPath(false, root, path).delete();
    }

    /** @return true if the cache file exists */
    public static boolean cacheFileExists(File root, String... path) {
        return openPath(false, root, path).exists();
    }

    /** @return a stream over the cache file; the caller must close it */
    public static InputStream openCacheFileForInput(File root, String... path) throws FileNotFoundException {
        return new BufferedInputStream(new FileInputStream(openPath(false, root, path)));
    }

    /** @return a stream writing to the cache file, creating directories as needed */
    public static OutputStream openCacheFileForOutput(File root, String... path) throws FileNotFoundException {
        return new BufferedOutputStream(new FileOutputStream(openPath(true, root, path)));
    }

    /**
     * Copies a stream, refusing to write more than {@code maxLength} bytes so a hostile or broken
     * host cannot fill the device's storage.
     */
    public static void writeInputStreamToOutputStream(InputStream in, OutputStream out, long maxLength) throws IOException {
        byte[] buf = new byte[4096];
        int bytesRead;

        while ((bytesRead = in.read(buf)) != -1) {
            maxLength -= bytesRead;
            if (maxLength <= 0) {
                throw new IOException("Stream exceeded max size");
            }
            out.write(buf, 0, bytesRead);
        }
    }

    /** @return the whole stream as a UTF-8 string */
    public static String readInputStreamToString(InputStream in) throws IOException {
        Reader r = new InputStreamReader(in);

        StringBuilder sb = new StringBuilder();
        char[] buf = new char[256];
        int bytesRead;
        while ((bytesRead = r.read(buf)) != -1) {
            sb.append(buf, 0, bytesRead);
        }

        try {
            in.close();
        } catch (IOException ignored) {}

        return sb.toString();
    }

    /** Writes a string to the stream as UTF-8. */
    public static void writeStringToOutputStream(OutputStream out, String str) throws IOException {
        out.write(str.getBytes("UTF-8"));
    }
}
