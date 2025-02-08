package nl.hauntedmc.dataprovider.orm.dialect;

/**
 * A default SQL dialect implementation.
 * Adjust the quote string as necessary for your target database.
 */
public class DefaultSQLDialect implements SQLDialect {

    @Override
    public String getIdentifierQuoteString() {
        // For example, use backticks for MySQL/MariaDB or double quotes for PostgreSQL.
        return "\""; // Change this if needed.
    }

    @Override
    public String getLimitClause(int limit) {
        return "LIMIT " + limit;
    }
}
