# DataProvider Examples

These examples are compiled with the API module, so their imports and public API calls stay current. They show lifecycle boundaries only; callers should compose returned `CompletableFuture` values with their platform scheduler and error handling.

- [RelationalSqlExample.java](RelationalSqlExample.java)
  - Register MySQL, execute parameterized SQL, and use typed/Optional scalar reads.
- [RelationalOrmExample.java](RelationalOrmExample.java)
  - Register a MySQL connection and initialize `ORMContext`.
- [RedisMessagingExample.java](RedisMessagingExample.java)
  - Register Redis Pub/Sub messaging and manage a logical subscription lifecycle.
- [RedisDurableMessagingExample.java](RedisDurableMessagingExample.java)
  - Capability-check durable messaging, consume idempotently, and retain the asynchronous publish result.
- [MongoDocumentExample.java](MongoDocumentExample.java)
  - Register MongoDB and perform Optional lookup plus document insert/update operations.
- [RedisKeyValueExample.java](RedisKeyValueExample.java)
  - Register Redis key-value and use duration-based expiry, fallback reads, and Optional cache lookup.

Use these files as templates in your plugin service/lifecycle classes.
