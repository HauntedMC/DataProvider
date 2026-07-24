package nl.hauntedmc.dataprovider.core.concurrent;

import javax.sql.DataSource;

/**
 * Marker for a DataSource whose connection acquisition remains inside DataProvider's bounded
 * execution and admission path. Implemented by both direct scoped views and stable recovery facades.
 */
public interface ScopedDataSource extends DataSource {
}
