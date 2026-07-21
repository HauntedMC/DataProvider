package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.core.database.document.impl.mongodb.MongoDBDatabase;
import nl.hauntedmc.dataprovider.core.database.keyvalue.impl.redis.RedisDatabase;
import nl.hauntedmc.dataprovider.core.database.messaging.impl.redis.RedisMessagingDatabase;
import nl.hauntedmc.dataprovider.core.database.relational.impl.mysql.MySQLDatabase;
import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseFactoryTest {

    @Test
    void constructorValidatesArguments() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = mock(DatabaseConfigMap.class);

        assertThrows(NullPointerException.class, () -> new DatabaseFactory(null, logger));
        assertThrows(NullPointerException.class, () -> new DatabaseFactory(configMap, null));
    }

    @Test
    void retainsTypedFailureAndLogsWhenConfigurationIsMissing() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = mock(DatabaseConfigMap.class);
        when(configMap.getConfig(DatabaseType.MYSQL, ConnectionIdentifier.of("missing"))).thenReturn(null);

        DatabaseFactory factory = new DatabaseFactory(configMap, logger);
        assertThrows(DataProviderExceptionMapper.MissingConfigurationFailure.class,
                () -> factory.createDatabaseProvider(DatabaseType.MYSQL, "missing"));
        assertTrue(logger.errorMessages().stream().anyMatch(m -> m.contains("Could not load configuration")));
    }

    @Test
    void createsProviderImplementationForEachDatabaseType() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = mock(DatabaseConfigMap.class);
        CommentedConfigurationNode node = CommentedConfigurationNode.root();

        for (DatabaseType type : DatabaseType.values()) {
            when(configMap.getConfig(type, ConnectionIdentifier.of("default"))).thenReturn(node);
        }

        DatabaseFactory factory = new DatabaseFactory(configMap, logger);

        assertInstanceOf(MySQLDatabase.class, factory.createDatabaseProvider(DatabaseType.MYSQL, "default"));
        assertInstanceOf(MongoDBDatabase.class, factory.createDatabaseProvider(DatabaseType.MONGODB, "default"));
        assertInstanceOf(RedisDatabase.class, factory.createDatabaseProvider(DatabaseType.REDIS, "default"));
        assertInstanceOf(RedisMessagingDatabase.class,
                factory.createDatabaseProvider(DatabaseType.REDIS_MESSAGING, "default"));
    }
}
