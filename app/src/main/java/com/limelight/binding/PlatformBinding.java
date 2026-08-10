package com.limelight.binding;

import android.content.Context;

import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.nvstream.http.LimelightCryptoProvider;

/** Supplies the Android implementations of the platform abstractions the streaming code needs. */
public class PlatformBinding {
    /** @return the crypto provider backed by this app's stored certificate and key */
    public static LimelightCryptoProvider getCryptoProvider(Context c) {
        return new AndroidCryptoProvider(c);
    }
}
