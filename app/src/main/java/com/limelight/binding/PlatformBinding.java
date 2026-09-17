package com.limelight.binding;

import android.content.Context;

import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.nvstream.http.LimelightCryptoProvider;

/** Supplies the Android implementations of the platform abstractions the streaming code needs. */
public class PlatformBinding {

    // One provider per process. Each instance re-reads and re-parses the client certificate and
    // RSA key from disk on first use, through BouncyCastle, and a fresh instance was being made
    // for every NvHTTP - once per 1.5 s host poll and twice on the launch path. The provider
    // caches its identity behind a global lock and holds nothing but two file paths, so sharing
    // it is safe and the application context is all it needs.
    private static volatile AndroidCryptoProvider cryptoProvider;

    /** @return the crypto provider backed by this app's stored certificate and key */
    public static LimelightCryptoProvider getCryptoProvider(Context c) {
        AndroidCryptoProvider provider = cryptoProvider;
        if (provider == null) {
            synchronized (PlatformBinding.class) {
                provider = cryptoProvider;
                if (provider == null) {
                    provider = new AndroidCryptoProvider(c.getApplicationContext());
                    cryptoProvider = provider;
                }
            }
        }
        return provider;
    }
}
