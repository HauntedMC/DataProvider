package nl.hauntedmc.dataprovider.database.security;

import org.junit.jupiter.api.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TlsSupportTest {

    @Test
    void createsDefaultSslContextThroughLegacyMethod() {
        SSLContext context = TlsSupport.createTrustAllSslContext();
        assertNotNull(context);
        assertNotNull(context.getSocketFactory());
    }

    @Test
    void createsDefaultSslContextWhenTrustStoreIsNotConfigured() {
        SSLContext context = TlsSupport.createSslContext(null, null, null);
        assertNotNull(context);
        assertNotNull(context.getSocketFactory());
    }

    @Test
    void trustAllHostnameVerifierReturnsStrictVerifier() {
        HostnameVerifier verifier = TlsSupport.trustAllHostnameVerifier();
        assertNotNull(verifier);
        assertEquals(HttpsURLConnection.getDefaultHostnameVerifier().getClass(), verifier.getClass());
    }

    @Test
    void strictHostnameVerifierUsesPlatformDefault() {
        HostnameVerifier verifier = TlsSupport.strictHostnameVerifier();
        assertNotNull(verifier);
        assertEquals(HttpsURLConnection.getDefaultHostnameVerifier().getClass(), verifier.getClass());
    }
}
