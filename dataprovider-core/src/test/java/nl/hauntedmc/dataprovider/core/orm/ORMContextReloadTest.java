package nl.hauntedmc.dataprovider.core.orm;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ORMContextReloadTest {
    @Test
    void currentEntityLoaderWinsOverProviderAndStaleThreadLoaders() throws Exception {
        var thread = Thread.currentThread();
        var original = thread.getContextClassLoader();
        try (var stale = entityLoader(); var current = entityLoader()) {
            Class<?> player = current.loadClass(Player.class.getName());
            Class<?> cooldown = current.loadClass(Cooldown.class.getName());
            thread.setContextClassLoader(stale);
            try (var context = new ORMContext("reload", source(), mock(LoggerAdapter.class), "none", player, cooldown)) {
                context.runInTransaction(session -> {
                    var metamodel = session.getSessionFactory().getMetamodel();
                    assertSame(player, metamodel.entity(player).getJavaType());
                    assertSame(cooldown, metamodel.entity(cooldown).getJavaType());
                    return null;
                });
                assertSame(stale, thread.getContextClassLoader());
            }
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    void failedMetadataInitializationDoesNotPoisonTheNextContext() throws Exception {
        var source = source();
        var failure = assertThrows(RuntimeException.class,
                () -> new ORMContext("invalid", source, mock(LoggerAdapter.class), "none", MissingId.class));
        assertInstanceOf(org.hibernate.AnnotationException.class, failure.getCause());
        try (var context = new ORMContext("valid", source, mock(LoggerAdapter.class), "none", Player.class)) {
            assertEquals(Player.class, context.runInTransaction(session ->
                    session.getSessionFactory().getMetamodel().entity(Player.class).getJavaType()));
        }
    }

    private static URLClassLoader entityLoader() {
        return new URLClassLoader(new java.net.URL[] {ORMContextReloadTest.class.getProtectionDomain().getCodeSource().getLocation()}, ORMContextReloadTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(ORMContextReloadTest.class.getName())) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
    }

    private static DataSource source() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var database = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        var statement = mock(java.sql.Statement.class);
        var result = mock(java.sql.ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(connection.getMetaData()).thenReturn(database);
        when(database.getDatabaseProductName()).thenReturn("MySQL");
        when(database.getDatabaseMajorVersion()).thenReturn(8);
        when(database.getDatabaseMinorVersion()).thenReturn(4);
        when(database.getDatabaseProductVersion()).thenReturn("8.4.0");
        when(database.getSQLKeywords()).thenReturn("");
        when(database.getConnection()).thenReturn(connection);
        return source;
    }

    @Entity(name = "ReloadPlayer")
    public static class Player {
        @Id public Long id;
    }

    @Entity(name = "ReloadCooldown")
    public static class Cooldown {
        @Id public Long id;
        @ManyToOne public Player player;
    }

    @Entity(name = "MissingId")
    public static class MissingId {
        public Long id;
    }
}
