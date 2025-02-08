package nl.hauntedmc.dataprovider.orm.dialect;

public class MySQLDialect implements SQLDialect {
    @Override
    public String getIdentifierQuoteString() {
        // Use backticks for MySQL.
        return "`";
    }

    @Override
    public String getLimitClause(int limit) {
        return "LIMIT " + limit;
    }
}
