/**
 * Copyright (C) 2019-2020, Zhichun Wu
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.github.clickhouse.bridge.jdbc;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;

import com.github.clickhouse.bridge.core.ClickHouseResponseWriter;
import com.github.clickhouse.bridge.core.ClickHouseBuffer;
import com.github.clickhouse.bridge.core.ClickHouseColumnInfo;
import com.github.clickhouse.bridge.core.ClickHouseColumnList;
import com.github.clickhouse.bridge.core.ClickHouseDataSource;
import com.github.clickhouse.bridge.core.ClickHouseDataType;
import com.github.clickhouse.bridge.core.QueryParameters;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.vertx.core.json.JsonObject;

public class ClickHouseJdbcDataSource extends ClickHouseDataSource {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClickHouseJdbcDataSource.class);

    private static final Properties DEFAULT_DATASOURCE_PROPERTIES = new Properties();

    private static final String PROP_POOL_NAME = "poolName";

    private static final String PROP_CLIENT_NAME = "ClientUser";
    private static final String DEFAULT_CLIENT_NAME = "clickhouse-datasource-bridge";

    private static final String QUERY_TABLE_BEGIN = "SELECT * FROM ";
    private static final String QUERY_TABLE_END = " WHERE 1 = 0";

    public static final String DATASOURCE_TYPE = "jdbc";

    static {
        // set default properties
        DEFAULT_DATASOURCE_PROPERTIES.setProperty("connectionTestQuery", "SELECT 1");
        DEFAULT_DATASOURCE_PROPERTIES.setProperty("minimumIdle", "1");
        DEFAULT_DATASOURCE_PROPERTIES.setProperty("maximumPoolSize", "5");
    }

    private final String jdbcUrl;
    private final HikariDataSource datasource;

    // cached identifier quote
    private String quoteIdentifier = null;

    public ClickHouseJdbcDataSource(String id, JsonObject config) {
        super(id, config);

        Properties props = new Properties();
        props.putAll(DEFAULT_DATASOURCE_PROPERTIES);

        if (id != null && id.startsWith(DATASOURCE_TYPE) && config == null) { // adhoc
            this.jdbcUrl = id;
            this.datasource = null;
        } else { // named
            if (config != null) {
                config.forEach(field -> {
                    if (!ClickHouseDataSource.CONF_TYPE.equals(field.getKey())) {
                        Object value = field.getValue();
                        if (value != null && !(value instanceof JsonObject)) {
                            props.setProperty(field.getKey(), String.valueOf(value));
                        }
                    }
                });
            }

            props.setProperty(PROP_POOL_NAME, id);

            this.jdbcUrl = null;
            this.datasource = new HikariDataSource(new HikariConfig(props));
        }
    }

    protected final Connection getConnection() throws SQLException {
        Connection conn = this.datasource != null ? this.datasource.getConnection()
                : DriverManager.getConnection(this.jdbcUrl);

        try {
            conn.setAutoCommit(true);
        } catch (Exception e) {
            log.warn("Failed enable auto commit due to {}", e.getMessage());
        }

        try {
            conn.setClientInfo(PROP_CLIENT_NAME, DEFAULT_CLIENT_NAME);
        } catch (Exception e) {
            log.warn("Failed call setClientInfo due to {}", e.getMessage());
        }

        return conn;
    }

    protected final Statement createStatement(Connection conn) throws SQLException {
        return createStatement(conn, null);
    }

    protected final Statement createStatement(Connection conn, QueryParameters parameters) throws SQLException {
        final Statement stmt;

        if (parameters == null) {
            stmt = conn.createStatement();
        } else {
            boolean scrollable = parameters.getOffset() != 0;
            stmt = conn.createStatement(scrollable ? ResultSet.TYPE_SCROLL_INSENSITIVE : ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY);

            stmt.setFetchSize(parameters.getFetchSize());
            stmt.setMaxRows(parameters.getMaxRows());
        }

        return stmt;
    }

    protected final void skipRows(ResultSet rs, QueryParameters parameters) throws SQLException {
        if (rs != null && parameters != null) {
            int position = parameters.getPosition();
            // absolute position takes priority
            if (position != 0) {
                log.debug("Move cursor position to row #{}...", position);
                rs.absolute(position);
                log.debug("Now resume reading...");
            } else {
                int offset = parameters.getOffset();

                if (offset > 0) {
                    log.debug("Skipping first {} rows...", offset);
                    while (rs.next()) {
                        if (--offset <= 0) {
                            break;
                        }
                    }
                    log.debug("Now resume reading the rest rows...");
                }
            }
        }
    }

    protected final ClickHouseDataType convert(int jdbcType, boolean signed) {
        return convert(JDBCType.valueOf(jdbcType), signed);
    }

    protected ClickHouseDataType convert(JDBCType jdbcType, boolean signed) {
        ClickHouseDataType type = ClickHouseDataType.String;

        switch (jdbcType) {
            case BIT:
            case BOOLEAN:
                type = ClickHouseDataType.UInt8;
                break;
            case TINYINT:
                type = signed ? ClickHouseDataType.Int8 : ClickHouseDataType.UInt8;
                break;
            case SMALLINT:
                type = signed ? ClickHouseDataType.Int16 : ClickHouseDataType.UInt16;
                break;
            case INTEGER:
                type = signed ? ClickHouseDataType.Int32 : ClickHouseDataType.UInt32;
                break;
            case BIGINT:
                type = signed ? ClickHouseDataType.Int64 : ClickHouseDataType.UInt64;
                break;
            case REAL:
            case FLOAT:
                type = ClickHouseDataType.Float32;
                break;
            case DOUBLE:
                type = ClickHouseDataType.Float64;
                break;
            case NUMERIC:
            case DECIMAL:
                type = ClickHouseDataType.Decimal;
                break;
            case CHAR:
            case NCHAR:
            case VARCHAR:
            case NVARCHAR:
            case LONGVARCHAR:
            case LONGNVARCHAR:
            case NULL:
                type = ClickHouseDataType.String;
                break;
            case DATE:
                type = ClickHouseDataType.Date;
                break;
            case TIME:
            case TIMESTAMP:
                type = ClickHouseDataType.DateTime;
                break;
            default:
                log.warn("Unsupported JDBC type [{}], which will be treated as [{}]", jdbcType.name(), type.name());
                break;
        }

        return type;
    }

    protected ResultSet getFirstQueryResult(Statement stmt, boolean hasResultSet) throws SQLException {
        ResultSet rs = null;

        if (hasResultSet) {
            rs = stmt.getResultSet();
        } else if (stmt.getUpdateCount() == -1) {
            throw new SQLException("No query result!");
        }

        return rs != null ? rs : getFirstQueryResult(stmt, stmt.getMoreResults());
    }

    @Override
    protected ClickHouseColumnList inferColumns(String schema, String query) {
        log.debug("Inferring database columns: schema=[{}], query=[{}]", schema, query);

        try (Connection conn = getConnection(); Statement stmt = createStatement(conn)) {
            stmt.setMaxRows(1);
            stmt.setFetchSize(1);

            // could be just a table name
            if (query != null && query.indexOf(' ') == -1) {
                StringBuilder sb = new StringBuilder().append(QUERY_TABLE_BEGIN);
                String quote = this.getQuoteIdentifier();
                if (schema != null) {
                    sb.append(quote).append(schema).append(quote).append('.');
                }
                query = sb.append(quote).append(query).append(quote).append(QUERY_TABLE_END).toString();
            }

            // could be very slow...
            ResultSetMetaData meta = getFirstQueryResult(stmt, stmt.execute(query)).getMetaData();

            ClickHouseColumnInfo[] columns = new ClickHouseColumnInfo[meta.getColumnCount()];

            for (int i = 1; i <= columns.length; i++) {
                columns[i - 1] = new ClickHouseColumnInfo(meta.getColumnName(i),
                        convert(meta.getColumnType(i), meta.isSigned(i)),
                        ResultSetMetaData.columnNoNulls != meta.isNullable(i), meta.getPrecision(i), meta.getScale(i));
            }

            return new ClickHouseColumnList(columns);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to get columns definition from database", e);
        }

        // return super.inferColumns(schema, query);
    }

    protected final void stream(ResultSet rs, ClickHouseColumnInfo[] columns, QueryParameters params,
            ClickHouseResponseWriter writer) throws SQLException {
        Objects.requireNonNull(rs);
        Objects.requireNonNull(columns);
        Objects.requireNonNull(writer);

        int length = columns.length;
        int estimatedSize = length * 4;

        while (rs.next()) {
            ClickHouseBuffer buffer = ClickHouseBuffer.newInstance(estimatedSize);
            for (int i = 1; i <= length; i++) {
                ClickHouseColumnInfo column = columns[i - 1];
                // keep in mind that column index is zero-based
                int index = column.isIndexed() ? column.getIndex() + 1 : i;

                if (column.isNullable()) {
                    if (rs.getObject(index) == null || rs.wasNull()) {
                        if (params.replaceNullAsDefault()) {
                            buffer.writeNonNull().writeDefaultValue(column);
                        } else {
                            buffer.writeNull();
                        }
                        continue;
                    } else {
                        buffer.writeNonNull();
                    }
                }

                switch (column.getType()) {
                    case Int8:
                        buffer.writeInt8(rs.getInt(index));
                        break;
                    case Int16:
                        buffer.writeInt16(rs.getInt(index));
                        break;
                    case Int32:
                        buffer.writeInt32(rs.getInt(index));
                        break;
                    case Int64:
                        buffer.writeInt64(rs.getLong(index));
                        break;
                    case UInt8:
                        buffer.writeUInt8(rs.getInt(index));
                        break;
                    case UInt16:
                        buffer.writeUInt16(rs.getInt(index));
                        break;
                    case UInt32:
                        buffer.writeUInt32(rs.getLong(index));
                        break;
                    case UInt64:
                        buffer.writeUInt64(rs.getLong(index));
                        break;
                    case Float32:
                        buffer.writeFloat32(rs.getFloat(index));
                        break;
                    case Float64:
                        buffer.writeFloat64(rs.getDouble(index));
                        break;
                    case DateTime:
                        buffer.writeDateTime(rs.getTimestamp(index));
                        break;
                    case Date:
                        buffer.writeDate(rs.getDate(index));
                        break;
                    case Decimal:
                        buffer.writeDecimal(rs.getBigDecimal(index), column.getPrecision(), column.getScale());
                        break;
                    case Decimal32:
                        buffer.writeDecimal32(rs.getBigDecimal(index), column.getScale());
                        break;
                    case Decimal64:
                        buffer.writeDecimal64(rs.getBigDecimal(index), column.getScale());
                        break;
                    case Decimal128:
                        buffer.writeDecimal128(rs.getBigDecimal(index), column.getScale());
                        break;
                    case String:
                    default:
                        buffer.writeString(rs.getString(index));
                        break;
                }
            }

            writer.write(buffer);
        }
    }

    @Override
    public final String getType() {
        return DATASOURCE_TYPE;
    }

    @Override
    public final String getQuoteIdentifier() {
        if (this.quoteIdentifier == null) {
            try (Connection conn = getConnection()) {
                quoteIdentifier = conn.getMetaData().getIdentifierQuoteString();
            } catch (SQLException e) {
                log.warn("Failed to get identifier quote string", e);

                return DEFAULT_QUOTE_IDENTIFIER;
            }
        }

        return this.quoteIdentifier;
    }

    @Override
    public void execute(String query, ClickHouseColumnList columns, QueryParameters params,
            ClickHouseResponseWriter writer) {
        log.info("Executing SQL:\n{}", query);

        try (Connection conn = getConnection(); Statement stmt = createStatement(conn, params)) {
            stream(getFirstQueryResult(stmt, stmt.execute(query)), columns.getColumns(), params, writer);
            /*
             * if (stmt.execute(query)) { // TODO multiple resultsets
             * 
             * } else if (columns.size() == 1 && columns.getColumn(0).getType() ==
             * ClickHouseDataType.Int32) {
             * writer.write(ClickHouseBuffer.newInstance(4).writeInt32(stmt.getUpdateCount()
             * )); } else { throw new IllegalStateException(
             * "Not able to handle query result due to incompatible columns: " + columns); }
             */
        } catch (Exception e) {
            log.error("Failed to execute SQL", e);
        }
    }

    @Override
    public void close() throws IOException {
        super.close();

        if (this.datasource != null) {
            this.datasource.close();
        }
    }
}