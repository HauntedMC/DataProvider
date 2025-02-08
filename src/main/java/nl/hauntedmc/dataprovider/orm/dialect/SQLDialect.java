package nl.hauntedmc.dataprovider.orm.dialect;

/**
 * Abstraction for SQL dialect differences.
 */
public interface SQLDialect {
    /**
     * Returns the quote string to wrap SQL identifiers.
     */
    String getIdentifierQuoteString();

    /**
     * Returns the LIMIT clause for the given limit.
     */
    String getLimitClause(int limit);
}
