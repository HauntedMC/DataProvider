package nl.hauntedmc.dataprovider.core.concurrent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionStateSnapshotTest {

    @Test
    void captureAndRestoreRoundTripsEveryMutableConnectionProperty() throws Exception {
        Connection connection = mock(Connection.class);
        Map<String, Class<?>> typeMap = new HashMap<>();
        typeMap.put("player", String.class);
        Properties clientInfo = new Properties();
        clientInfo.setProperty("application", "dataprovider");
        stubState(connection, typeMap, clientInfo);

        ConnectionStateSnapshot snapshot = ConnectionStateSnapshot.capture(connection);
        typeMap.put("changed", Integer.class);
        clientInfo.setProperty("application", "changed");
        Properties currentClientInfo = new Properties();
        currentClientInfo.setProperty("application", "mutated");
        currentClientInfo.setProperty("leaked", "remove-me");
        when(connection.getClientInfo()).thenReturn(currentClientInfo);

        snapshot.restore(connection);

        verify(connection).setReadOnly(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        verify(connection).setCatalog("catalog");
        ArgumentCaptor<Map<String, Class<?>>> restoredTypes = typeMapCaptor();
        verify(connection).setTypeMap(restoredTypes.capture());
        assertEquals(Map.of("player", String.class), restoredTypes.getValue());
        assertNotSame(typeMap, restoredTypes.getValue());
        verify(connection).setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
        verify(connection).setSchema("schema");
        verify(connection).setNetworkTimeout(any(Executor.class), eq(2_500));
        verify(connection).setClientInfo("leaked", null);
        verify(connection).setClientInfo("application", "dataprovider");
        verify(connection).setAutoCommit(false);
    }

    @Test
    void restoreUsesASecondDefensiveTypeMapCopyOnEveryInvocation() throws Exception {
        Connection connection = mock(Connection.class);
        stubState(connection, new HashMap<>(Map.of("type", String.class)), new Properties());
        ConnectionStateSnapshot snapshot = ConnectionStateSnapshot.capture(connection);

        snapshot.restore(connection);
        snapshot.restore(connection);

        ArgumentCaptor<Map<String, Class<?>>> restoredTypes = typeMapCaptor();
        verify(connection, org.mockito.Mockito.times(2)).setTypeMap(restoredTypes.capture());
        assertEquals(restoredTypes.getAllValues().get(0), restoredTypes.getAllValues().get(1));
        assertNotSame(restoredTypes.getAllValues().get(0), restoredTypes.getAllValues().get(1));
    }

    @Test
    void nullTypeMapAndClientInfoAreRestoredWithoutInventingState() throws Exception {
        Connection connection = mock(Connection.class);
        stubState(connection, null, null);
        when(connection.getClientInfo()).thenReturn(null);
        ConnectionStateSnapshot snapshot = ConnectionStateSnapshot.capture(connection);

        snapshot.restore(connection);

        verify(connection).setTypeMap(null);
        verify(connection, org.mockito.Mockito.never()).setClientInfo(any(String.class), any(String.class));
    }

    @Test
    void restoreContinuesAfterFailuresAndSuppressesLaterFailures() throws Exception {
        Connection connection = mock(Connection.class);
        stubState(connection, Map.of(), new Properties());
        ConnectionStateSnapshot snapshot = ConnectionStateSnapshot.capture(connection);
        SQLException first = new SQLException("read-only restore failed");
        SQLException second = new SQLException("schema restore failed");
        doThrow(first).when(connection).setReadOnly(true);
        doThrow(second).when(connection).setSchema("schema");

        SQLException thrown = assertThrows(SQLException.class, () -> snapshot.restore(connection));

        assertSame(first, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(second, thrown.getSuppressed()[0]);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        verify(connection).setCatalog("catalog");
        verify(connection).setTypeMap(any());
        verify(connection).setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
        verify(connection).setNetworkTimeout(any(Executor.class), eq(2_500));
        verify(connection).setAutoCommit(false);
    }

    @Test
    void autoCommitIsAlwaysRestoredAfterEveryOtherConnectionProperty() throws Exception {
        Connection connection = mock(Connection.class);
        stubState(connection, Map.of(), new Properties());
        ConnectionStateSnapshot snapshot = ConnectionStateSnapshot.capture(connection);

        snapshot.restore(connection);

        InOrder order = inOrder(connection);
        order.verify(connection).setReadOnly(true);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        order.verify(connection).setCatalog("catalog");
        order.verify(connection).setTypeMap(any());
        order.verify(connection).setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
        order.verify(connection).setSchema("schema");
        order.verify(connection).setNetworkTimeout(any(Executor.class), eq(2_500));
        order.verify(connection).setClientInfo("application", "dataprovider");
        order.verify(connection).setAutoCommit(false);
    }

    @Test
    void capturePropagatesDriverFailureWithoutProducingAPartialSnapshot() throws Exception {
        Connection connection = mock(Connection.class);
        SQLException failure = new SQLException("cannot read isolation");
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenThrow(failure);

        SQLException thrown = assertThrows(SQLException.class, () -> ConnectionStateSnapshot.capture(connection));

        assertSame(failure, thrown);
        verify(connection, org.mockito.Mockito.never()).getCatalog();
    }

    private static void stubState(
            Connection connection,
            Map<String, Class<?>> typeMap,
            Properties clientInfo
    ) throws Exception {
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
        when(connection.getCatalog()).thenReturn("catalog");
        when(connection.getTypeMap()).thenReturn(typeMap);
        when(connection.getHoldability()).thenReturn(ResultSet.HOLD_CURSORS_OVER_COMMIT);
        when(connection.getSchema()).thenReturn("schema");
        when(connection.getNetworkTimeout()).thenReturn(2_500);
        when(connection.getClientInfo()).thenReturn(clientInfo);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Class<?>>> typeMapCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, Class<?>>>) (Class<?>) Map.class);
    }
}
