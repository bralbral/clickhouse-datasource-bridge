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
package com.github.clickhouse.bridge.core;

import java.util.Objects;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;

import static com.github.clickhouse.bridge.core.ClickHouseUtils.EMPTY_STRING;

public class QueryParser {
    private static final String PARAM_CONNECTION_STRING = "connection_string";
    private static final String PARAM_SCHEMA = "schema";
    private static final String PARAM_TABLE = "table";
    // If it is set to true, external table functions will implicitly use Nullable
    // type if needed.
    // Otherwise NULLs will be substituted with default values.
    // Currently supported only for 'mysql' table function.
    private static final String PARAM_EXT_TABLE_USE_NULLS = "external_table_functions_use_nulls";
    private static final String PARAM_COLUMNS = "columns";
    private static final String PARAM_QUERY = "query";
    private static final String PARAM_DB_NAME = "db_name";
    private static final String PARAM_TABLE_NAME = "table_name";
    private static final String PARAM_FORMAT_NAME = "format_name";

    private static final String KEYWORD_FROM = "FROM";

    private static final String EXPR_QUERY = PARAM_QUERY + "=";
    private static final String EXPR_FROM = " " + KEYWORD_FROM + " ";

    private static final String FORMAT_ROW_BINARY = "RowBinary";

    private static final String SINGLE_LINE_COMMENT = "--";
    private static final String MULTILINE_COMMENT_BEGIN = "/*";
    private static final String MULTILINE_COMMENT_END = "*/";
    private static final String DOUBLE_QUOTES_STRING = "''";
    private static final String ESCAPED_QUOTE_STRING = "\\'";

    private final String uri;
    private final String schema;
    private final String table;
    private final String columnsInfo;
    private final String inputFormat;
    private final boolean useNull;

    private final StreamOptions options;

    private String normalizedQuery = null;
    private QueryParameters queryParams = null;
    private ClickHouseColumnList columns = null;

    private QueryParser(String uri, String schema, String table, String columnsInfo, String inputFormat, String useNull,
            MultiMap params) {
        this.uri = uri;
        this.schema = schema;
        this.table = table;
        this.columnsInfo = columnsInfo;
        this.inputFormat = inputFormat;
        this.useNull = Boolean.parseBoolean(useNull);
        this.options = new StreamOptions(params);
    }

    public String getConnectionString() {
        return this.uri;
    }

    public String getRawQuery() {
        return this.table;
    }

    public String getSchema() {
        return this.schema;
    }

    public String extractTable(String normalizedQuery) {
        String tableName = extractTableName(normalizedQuery);

        if (tableName == null) {
            tableName = extractTableName(this.table);
        }
        return tableName == null ? this.table : tableName;
    }

    public boolean usingRowBinaryInput() {
        return FORMAT_ROW_BINARY.equals(this.inputFormat);
    }

    public StreamOptions getStreamOptions() {
        return this.options;
    }

    public ClickHouseColumnList getColumnList() {
        if (this.columns == null) {
            this.columns = ClickHouseColumnList.fromString(this.columnsInfo);
        }

        return this.columns;
    }

    public QueryParameters getQueryParameters() {
        if (this.queryParams == null) {
            this.queryParams = new QueryParameters(this.uri);
        }

        return this.queryParams;
    }

    public String getNormalizedQuery() {
        if (this.normalizedQuery == null) {
            this.normalizedQuery = normalizeQuery(this.table);
        }

        return this.normalizedQuery;
    }

    static String extractTableName(String query) {
        if (query == null || query.length() == 0) {
            return query;
        }

        String table = query;
        int len = query.length();
        int index = -1;

        boolean quoteStarted = false;
        for (int i = 0; i < len; i++) {
            String nextTwo = query.substring(i, Math.min(i + 2, len));
            if (SINGLE_LINE_COMMENT.equals(nextTwo)) {
                int newIdx = query.indexOf("\n", i);
                i = newIdx != -1 ? Math.max(i, newIdx) : len;
            } else if (MULTILINE_COMMENT_BEGIN.equals(nextTwo)) {
                int newIdx = query.indexOf(MULTILINE_COMMENT_END, i);
                i = newIdx != -1 ? newIdx + 1 : len;
            } else if (DOUBLE_QUOTES_STRING.equals(nextTwo) || ESCAPED_QUOTE_STRING.equals(nextTwo)) {
                // ignore escaped single quote
                i += nextTwo.length() - 1;
            } else if (nextTwo.charAt(0) == '\'') {
                if (quoteStarted = !quoteStarted) {
                    i += nextTwo.length() - 1;
                }
            } else if (!quoteStarted) {
                char ch = nextTwo.charAt(0);

                if (index > 0 && ch == '(') {
                    index = 0;
                } else if (Character.isWhitespace(ch)) {
                    if (index > 0) {
                        if (index + 1 == i) {
                            index = i;
                        } else {
                            table = query.substring(index + 1, i);
                            index = -1;
                            break;
                        }
                    } else {
                        index = i + KEYWORD_FROM.length() + 1;
                        if (index < len) {
                            String str = query.substring(i + 1, index);
                            if (KEYWORD_FROM.equalsIgnoreCase(str) && Character.isWhitespace(query.charAt(index))) {
                                i = index;
                            } else {
                                index = 0;
                            }
                        } else {
                            break;
                        }
                    }
                } else {
                    continue;
                }
            }
        }

        if (index > 0) {
            table = query.substring(index + 1);
        } else if (index != -1) {
            table = null;
        }

        return table;
    }

    public static String extractConnectionString(RoutingContext ctx, IDataSourceResolver resolver) {
        HttpServerRequest req = Objects.requireNonNull(ctx).request();
        return Objects.requireNonNull(resolver).resolve(req.getParam(PARAM_CONNECTION_STRING));
    }

    public static QueryParser fromRequest(RoutingContext ctx, IDataSourceResolver resolver) {
        return fromRequest(ctx, resolver, false);
    }

    public static QueryParser fromRequest(RoutingContext ctx, IDataSourceResolver resolver, boolean forWrite) {
        HttpServerRequest req = Objects.requireNonNull(ctx).request();

        final QueryParser query;

        String uri = Objects.requireNonNull(resolver).resolve(req.getParam(PARAM_CONNECTION_STRING));
        if (forWrite) {
            query = new QueryParser(uri, req.getParam(PARAM_DB_NAME), req.getParam(PARAM_TABLE_NAME),
                    req.getParam(PARAM_COLUMNS), req.getParam(PARAM_FORMAT_NAME), null, null);
        } else {
            String schema = req.getParam(PARAM_SCHEMA);
            String table = req.getParam(PARAM_TABLE);

            if (schema == null) {
                schema = EMPTY_STRING;
            }
            if (table == null) {
                table = ctx.getBodyAsString();

                // remove optional prefix
                if (table != null && table.startsWith(EXPR_QUERY)) {
                    table = table.substring(EXPR_QUERY.length());
                } else {
                    table = EMPTY_STRING;
                }
            }

            query = new QueryParser(uri, schema, table, req.getParam(PARAM_COLUMNS), null,
                    req.getParam(PARAM_EXT_TABLE_USE_NULLS), req.params());
        }

        return query;
    }

    static String normalizeQuery(String query) {
        String normalizedQuery = Objects.requireNonNull(query);

        // since we checked if this could be a named query before calling this method,
        // we know the extracted query will be either a table name or an adhoc query
        String extractedQuery = null;
        int len = query.length();
        int index = query.indexOf(EXPR_FROM);
        if (index > 0 && len > (index = index + EXPR_FROM.length())) {
            // assume quote is just one character and it always exists
            char quote = query.charAt(index++);

            int dotIndex = query.indexOf('.', index);

            if (dotIndex > index && len > dotIndex && query.charAt(dotIndex - 1) == quote
                    && query.charAt(dotIndex + 1) == quote) { // has schema
                dotIndex += 2;
                /*
                 * int endIndex = query.indexOf(quote, dotIndex); // .lastIndexOf(quote); if
                 * (endIndex > dotIndex) { extractedQuery = query.substring(dotIndex, endIndex);
                 * }
                 */
            } else if (quote == '"' || quote == '`') {
                dotIndex = index;
                /*
                 * int endIndex = query.indexOf(quote, index); // query.lastIndexOf(quote); if
                 * (endIndex > index) { extractedQuery = query.substring(index, endIndex); }
                 */
            } else {
                dotIndex = len;
            }

            boolean quoteStarted = false;
            for (int i = dotIndex; i < len; i++) {
                String nextTwo = query.substring(i, Math.min(i + 2, len));

                if (SINGLE_LINE_COMMENT.equals(nextTwo)) {
                    int newIdx = query.indexOf("\n", i);
                    i = newIdx != -1 ? Math.max(i, newIdx) : len;
                } else if (MULTILINE_COMMENT_BEGIN.equals(nextTwo)) {
                    int newIdx = query.indexOf(MULTILINE_COMMENT_END, i);
                    i = newIdx != -1 ? newIdx + 1 : len;
                } else if (DOUBLE_QUOTES_STRING.equals(nextTwo) || ESCAPED_QUOTE_STRING.equals(nextTwo)) {
                    // ignore escaped single quote
                    i += nextTwo.length() - 1;
                } else if (nextTwo.charAt(0) == '\'') {
                    if (quoteStarted = !quoteStarted) {
                        i += nextTwo.length() - 1;
                    }
                } else if (!quoteStarted && nextTwo.charAt(0) == quote) {
                    extractedQuery = query.substring(dotIndex, i);
                    break;
                }
            }
        }

        normalizedQuery = extractedQuery != null ? extractedQuery.trim() : normalizedQuery.trim();
        len = normalizedQuery.length();

        // unescape String is mission impossible so we only considered below ones:
        // \t Insert a tab in the text at this point.
        // \b Insert a backspace in the text at this point.
        // \n Insert a newline in the text at this point.
        // \r Insert a carriage return in the text at this point.
        // \f Insert a formfeed in the text at this point.
        // \' Insert a single quote character in the text at this point.
        // \" Insert a double quote character in the text at this point.
        // \\ Insert a backslash character in the text at this point.
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < len; i++) {
            char ch = normalizedQuery.charAt(i);
            if (ch == '\\' && i + 1 < len) {
                char nextCh = normalizedQuery.charAt(i + 1);
                switch (nextCh) {
                    case 't':
                        builder.append('\t');
                        i++;
                        break;
                    case 'b':
                        builder.append('\b');
                        i++;
                        break;
                    case 'n':
                        builder.append('\n');
                        i++;
                        break;
                    case 'r':
                        builder.append('\r');
                        i++;
                        break;
                    case 'f':
                        builder.append('\f');
                        i++;
                        break;
                    case '\'':
                        builder.append('\'');
                        i++;
                        break;
                    case '"':
                        builder.append('"');
                        i++;
                        break;
                    case '\\':
                        builder.append('\\');
                        i++;
                        break;
                    default:
                        builder.append(ch);
                        break;
                }
            } else {
                builder.append(ch);
            }
        }

        return builder.toString().trim();
    }
}