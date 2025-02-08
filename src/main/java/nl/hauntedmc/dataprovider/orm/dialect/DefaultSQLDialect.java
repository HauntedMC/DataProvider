package nl.hauntedmc.dataprovider.orm.dialect;

/**
 * A default SQL dialect implementation.
 * Adjust the quote string as necessary for your target database.
 */
public class DefaultSQLDialect implements SQLDialect {

    @Override
    public String getIdentifierQuoteString() {
        // Default to double quotes; adjust as needed (e.g., backticks for MySQL/MariaDB).
        return "\"";
    }

    @Override
    public String getLimitClause(int limit) {
        return "LIMIT " + limit;
    }
}
