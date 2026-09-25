package top.cheesesmp.duelcore.db;

/** SQL differences between SQLite and MySQL/MariaDB. */
public enum Dialect {
    SQLITE,
    MYSQL;

    public String autoIncrementPk(String column, boolean big) {
        return switch (this) {
            case SQLITE -> column + " INTEGER PRIMARY KEY AUTOINCREMENT";
            case MYSQL -> column + (big ? " BIGINT" : " INT") + " NOT NULL AUTO_INCREMENT PRIMARY KEY";
        };
    }

    public String uuidType() {
        return this == SQLITE ? "BLOB" : "BINARY(16)";
    }

    /** Table suffix: clustered primary key storage where supported. */
    public String clustered() {
        return this == SQLITE ? " WITHOUT ROWID" : " ENGINE=InnoDB";
    }

    public String tableSuffix() {
        return this == SQLITE ? "" : " ENGINE=InnoDB";
    }

    /**
     * Upsert tail for the given conflict columns and updated columns.
     * SQLite: ON CONFLICT(..) DO UPDATE SET c=excluded.c; MySQL/MariaDB: ON DUPLICATE KEY UPDATE c=VALUES(c).
     */
    public String upsert(String conflictColumns, String... updated) {
        StringBuilder sb = new StringBuilder();
        if (this == SQLITE) {
            sb.append(" ON CONFLICT(").append(conflictColumns).append(") DO UPDATE SET ");
            for (int i = 0; i < updated.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(updated[i]).append("=excluded.").append(updated[i]);
            }
        } else {
            sb.append(" ON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < updated.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(updated[i]).append("=VALUES(").append(updated[i]).append(")");
            }
        }
        return sb.toString();
    }

    public String insertIgnore() {
        return this == SQLITE ? "INSERT OR IGNORE" : "INSERT IGNORE";
    }
}
