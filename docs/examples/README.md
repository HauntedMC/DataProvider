# DataProvider Examples

These examples are compiled with the API module, so their imports and public API calls stay current. They show lifecycle boundaries only; callers should compose returned `CompletableFuture` values with their platform scheduler and error handling.

- [RelationalSqlExample.java](RelationalSqlExample.java)
  - Register MySQL, execute parameterized SQL, and read a typed scalar result.
- [RelationalOrmExample.java](RelationalOrmExample.java)
  - Register a MySQL connection and initialize `ORMContext`.
- [RedisMessagingExample.java](RedisMessagingExample.java)
- [RedisDurableMessagingExample.java](RedisDurableMessagingExample.java)
  - Register Redis messaging, subscribe, publish, and unregister through the API lifecycle.
- [MongoDocumentExample.java](MongoDocumentExample.java)
  - Register MongoDB and perform document insert, lookup, and update operations.
- [RedisKeyValueExample.java](RedisKeyValueExample.java)
  - Register Redis key-value and use duration-based expiry plus fallback reads.

Use these files as templates in your plugin service/lifecycle classes.
