# DataProvider Examples

These examples are intentionally minimal and focused on API usage patterns.

- [RelationalOrmExample.java](RelationalOrmExample.java)
  - Register a MySQL connection and initialize `ORMContext`.
- [RedisMessagingExample.java](RedisMessagingExample.java)
  - Register Redis messaging, subscribe, publish, and unsubscribe cleanly.
- [MongoDocumentExample.java](MongoDocumentExample.java)
  - Register MongoDB and perform simple document insert/find operations.
- [RedisKeyValueExample.java](RedisKeyValueExample.java)
  - Register Redis key-value and perform basic cache reads/writes.

Use these files as templates in your plugin service/lifecycle classes.
