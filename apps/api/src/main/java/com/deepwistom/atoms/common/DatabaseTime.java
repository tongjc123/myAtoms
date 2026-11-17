package com.deepwistom.atoms.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class DatabaseTime {
    private DatabaseTime() {}
    public static LocalDateTime write(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
    public static Instant read(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
