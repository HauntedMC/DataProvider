package nl.hauntedmc.dataprovider.database;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseProviderDefaultsTest {

    @Test
    void requireDataAccessReturnsExpectedType() {
        DatabaseProvider provider = new StubDatabaseProvider(new StringDataAccess(), null, TestDataSource::new);
        assertInstanceOf(StringDataAccess.class, provider.requireDataAccess(StringDataAccess.class));
    }

    @Test
    void requireDataAccessRejectsNullDataAccessWithDescriptiveError() {
        DatabaseProvider provider = new StubDatabaseProvider(null, null, TestDataSource::new);
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> provider.requireDataAccess(StringDataAccess.class)
        );
        assertTrue(ex.getMessage().contains("provider returned null"));
    }

    @Test
    void requireDataAccessRejectsIncompatibleType() {
        DatabaseProvider provider = new StubDatabaseProvider(new OtherDataAccess(), null, TestDataSource::new);
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> provider.requireDataAccess(StringDataAccess.class)
        );
        assertTrue(ex.getMessage().contains(StringDataAccess.class.getName()));
        assertTrue(ex.getMessage().contains(OtherDataAccess.class.getName()));
    }

    @Test
    void getDataAccessOptionalReturnsEmptyWhenProviderIsNotReady() {
        DatabaseProvider provider = new StubDatabaseProvider(
                null,
                new IllegalStateException("not ready"),
                TestDataSource::new
        );
        assertEquals(Optional.empty(), provider.getDataAccessOptional(StringDataAccess.class));
    }

    @Test
    void getDataSourceOptionalReturnsEmptyWhenUnsupported() {
        DatabaseProvider provider = new StubDatabaseProvider(
                new StringDataAccess(),
                null,
                () -> {
                    throw new UnsupportedOperationException("unsupported");
                }
        );
        assertEquals(Optional.empty(), provider.getDataSourceOptional());
    }

    @Test
    void getDataSourceOptionalReturnsEmptyWhenNotReady() {
        DatabaseProvider provider = new StubDatabaseProvider(
                new StringDataAccess(),
                null,
                () -> {
                    throw new IllegalStateException("not ready");
                }
        );
        assertEquals(Optional.empty(), provider.getDataSourceOptional());
    }

    @Test
    void getDataSourceOptionalReturnsPresentWhenAvailable() {
        TestDataSource dataSource = new TestDataSource();
        DatabaseProvider provider = new StubDatabaseProvider(new StringDataAccess(), null, () -> dataSource);
        Optional<DataSource> optional = provider.getDataSourceOptional();
        assertTrue(optional.isPresent());
        assertEquals(dataSource, optional.get());
    }

    private static final class StringDataAccess implements DataAccess {
    }

    private static final class OtherDataAccess implements DataAccess {
    }

    private static final class StubDatabaseProvider implements DatabaseProvider {
        private final DataAccess dataAccess;
        private final RuntimeException dataAccessFailure;
        private final Supplier<DataSource> dataSourceSupplier;

        private StubDatabaseProvider(
                DataAccess dataAccess,
                RuntimeException dataAccessFailure,
                Supplier<DataSource> dataSourceSupplier
        ) {
            this.dataAccess = dataAccess;
            this.dataAccessFailure = dataAccessFailure;
            this.dataSourceSupplier = dataSourceSupplier;
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public DataAccess getDataAccess() {
            if (dataAccessFailure != null) {
                throw dataAccessFailure;
            }
            return dataAccess;
        }

        @Override
        public DataSource getDataSource() {
            return dataSourceSupplier.get();
        }
    }

    private static final class TestDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException("Not needed for this test.");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new UnsupportedOperationException("Not needed for this test.");
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("No parent logger.");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("Not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
