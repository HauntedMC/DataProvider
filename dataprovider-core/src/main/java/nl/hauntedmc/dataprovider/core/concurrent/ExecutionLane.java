package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.database.DatabaseType;

/** Runtime execution isolation lanes. */
public enum ExecutionLane {
    RELATIONAL,
    DOCUMENT,
    REDIS,
    MESSAGING;

    public static ExecutionLane forDatabaseType(DatabaseType type) {
        return switch (type) {
            case MYSQL -> RELATIONAL;
            case MONGODB -> DOCUMENT;
            case REDIS -> REDIS;
            case REDIS_MESSAGING -> MESSAGING;
        };
    }
}
