package nl.hauntedmc.dataprovider.database.security;

import org.junit.jupiter.api.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TlsSupportTest {

    @Test
    void createsDefaultSslContextWhenTrustStoreIsNotConfigured() {
        SSLContext context = TlsSupport.createSslContext(null, null, null);
        assertNotNull(context);
        assertNotNull(context.getSocketFactory());
    }

    @Test
    void strictHostnameVerifierUsesPlatformDefault() {
        HostnameVerifier verifier = TlsSupport.strictHostnameVerifier();
        assertNotNull(verifier);
        assertEquals(HttpsURLConnection.getDefaultHostnameVerifier().getClass(), verifier.getClass());
    }
}
