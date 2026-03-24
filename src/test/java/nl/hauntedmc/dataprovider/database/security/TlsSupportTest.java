package nl.hauntedmc.dataprovider.database.security;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsSupportTest {

    @Test
    void createsTrustAllSslContext() {
        SSLContext context = TlsSupport.createTrustAllSslContext();
        assertNotNull(context);
        assertNotNull(context.getSocketFactory());
    }

    @Test
    void trustAllHostnameVerifierAlwaysReturnsTrue() {
        assertTrue(TlsSupport.trustAllHostnameVerifier().verify("example.org", null));
        assertTrue(TlsSupport.trustAllHostnameVerifier().verify("", null));
    }
}
