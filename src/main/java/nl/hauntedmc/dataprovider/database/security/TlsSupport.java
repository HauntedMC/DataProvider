package nl.hauntedmc.dataprovider.database.security;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Shared TLS helpers for optional "trust all" compatibility modes.
 */
public final class TlsSupport {

    private static final X509TrustManager TRUST_ALL_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Intentionally no-op for compatibility mode.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Intentionally no-op for compatibility mode.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private static final HostnameVerifier TRUST_ALL_HOSTNAME_VERIFIER = (hostname, session) -> true;

    private TlsSupport() {
    }

    public static SSLContext createTrustAllSslContext() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{TRUST_ALL_MANAGER}, new SecureRandom());
            return sslContext;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to initialize trust-all TLS context.", e);
        }
    }

    public static HostnameVerifier trustAllHostnameVerifier() {
        return TRUST_ALL_HOSTNAME_VERIFIER;
    }
}
