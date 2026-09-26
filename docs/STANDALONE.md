# Standalone DataProvider host

`dataprovider-standalone` runs the existing DataProvider core in a plain Java process. It has no Spring, Paper, or Velocity dependency. The application supplies a logger, an owner ID, and an explicitly named MySQL connection; `open(...)` returns an owner-bound `DataProviderAPI`. Close the host with the application lifecycle.

The host writes DataProvider's validated configuration into an owner-only temporary directory for bootstrap, then removes the files after core has loaded them. It does not expose runtime configuration reload. `orm.schema_mode` is fixed to `validate` so a service cannot silently migrate a plugin-owned schema. Use a separate database user with only the privileges the service needs. The first consumer, WebApp, uses a read-only user and exposes only DataRegistry's `ReadOnlyPlayerProfiles` projection.

The core APIs and resilience machinery are shared with the plugin runtimes. The standalone host currently provisions one named MySQL connection. Add further typed connection options when a concrete non-plugin consumer needs them; keep application-framework wiring in the application rather than importing Spring into DataProvider.
