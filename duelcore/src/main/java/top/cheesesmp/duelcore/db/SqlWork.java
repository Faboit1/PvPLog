package top.cheesesmp.duelcore.db;

import java.sql.Connection;
import java.sql.SQLException;

/** A unit of database work executed on the DB thread with a pooled connection. */
@FunctionalInterface
public interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
}
