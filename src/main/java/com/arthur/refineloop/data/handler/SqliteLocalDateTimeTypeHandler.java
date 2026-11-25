package com.arthur.refineloop.data.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * {@link LocalDateTime} ↔ SQLite 的类型处理器。
 *
 * <p>为什么需要自定义：sqlite-jdbc 的 {@code setObject(LocalDateTime)} 行为不稳定，
 * 而我们用 {@code date_class=TEXT} 让时间以 {@code 'yyyy-MM-dd HH:mm:ss'} 文本存储
 * （与 schema 的 {@code DEFAULT (datetime('now','localtime'))} 同口径）。
 * 这里显式走 {@link PreparedStatement#setTimestamp} / {@link ResultSet#getTimestamp}，
 * 保证「写进去是文本、读出来是 LocalDateTime」，且与 {@code created_at >= ?} 这类文本比较一致。
 */
@MappedTypes(LocalDateTime.class)
public class SqliteLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setTimestamp(i, Timestamp.valueOf(parameter));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toLocalDateTime(rs.getTimestamp(columnName));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toLocalDateTime(rs.getTimestamp(columnIndex));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toLocalDateTime(cs.getTimestamp(columnIndex));
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
