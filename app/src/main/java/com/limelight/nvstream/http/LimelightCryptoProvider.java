package com.limelight.nvstream.http;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Supplies this client's persistent identity: the certificate and private key used to
 * authenticate to hosts.
 *
 * <p>Abstracted because the identity has to survive across sessions and be stored per platform;
 * the Android implementation is {@code AndroidCryptoProvider}. The same certificate must be
 * presented on every connection, because the host pairs against it — regenerating it unpairs the
 * client from every host it knows.
 */
public interface LimelightCryptoProvider {
    X509Certificate getClientCertificate();
    PrivateKey getClientPrivateKey();
    byte[] getPemEncodedClientCertificate();
    String encodeBase64String(byte[] data);
}
