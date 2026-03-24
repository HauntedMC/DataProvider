package nl.hauntedmc.dataprovider.database.security;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Shared TLS helpers for secure client TLS configuration.
 */
public final class TlsSupport {

    private static final HostnameVerifier STRICT_HOSTNAME_VERIFIER = HttpsURLConnection.getDefaultHostnameVerifier();

    private TlsSupport() {
    }

    /**
     * Legacy method name kept for API compatibility.
     * Uses platform trust by default and never disables certificate validation.
     */
    @Deprecated(since = "1.21.0", forRemoval = false)
    public static SSLContext createTrustAllSslContext() {
        return createSslContext(null, null, null);
    }

    /**
     * Creates an SSL context that either:
     * - uses platform default trust managers when no trust store path is provided; or
     * - uses trust managers from the configured trust store.
     */
    public static SSLContext createSslContext(String trustStorePath, String trustStorePassword, String trustStoreType) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");

            if (trustStorePath == null || trustStorePath.isBlank()) {
                sslContext.init(null, null, new SecureRandom());
                return sslContext;
            }

            String resolvedStoreType =
                    (trustStoreType == null || trustStoreType.isBlank()) ? KeyStore.getDefaultType() : trustStoreType;
            KeyStore trustStore = KeyStore.getInstance(resolvedStoreType);
            char[] passwordChars = trustStorePassword == null ? null : trustStorePassword.toCharArray();

            try (InputStream trustStoreStream =
                         Files.newInputStream(Path.of(trustStorePath).toAbsolutePath().normalize())) {
                trustStore.load(trustStoreStream, passwordChars);
            } finally {
                if (passwordChars != null) {
                    Arrays.fill(passwordChars, '\0');
                }
            }

            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
            return sslContext;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Unable to initialize TLS context.", e);
        }
    }

    /**
     * Returns the platform strict hostname verifier.
     */
    public static HostnameVerifier strictHostnameVerifier() {
        return STRICT_HOSTNAME_VERIFIER;
    }

    /**
     * Legacy method name kept for API compatibility.
     * Always returns a strict verifier.
     */
    @Deprecated(since = "1.21.0", forRemoval = false)
    public static HostnameVerifier trustAllHostnameVerifier() {
        return strictHostnameVerifier();
    }
}
