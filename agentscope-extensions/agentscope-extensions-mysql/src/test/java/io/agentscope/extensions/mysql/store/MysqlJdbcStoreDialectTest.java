/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.mysql.store;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MysqlJdbcStoreDialectTest {

    private static final int INNODB_UTF8MB4_INDEX_LIMIT_BYTES = 3072;
    private static final int UTF8MB4_MAX_BYTES_PER_CHAR = 4;

    @Test
    void createTablePrimaryKeyFitsInnoDbUtf8mb4Limit() {
        String ddl = new MysqlJdbcStoreDialect().getCreateTableSql();

        int namespacePathLength = varcharLength(ddl, "namespace_path");
        int itemKeyLength = varcharLength(ddl, "item_key");
        long compositePkBytes =
                (long) (namespacePathLength + itemKeyLength) * UTF8MB4_MAX_BYTES_PER_CHAR;

        assertTrue(
                compositePkBytes <= INNODB_UTF8MB4_INDEX_LIMIT_BYTES,
                () ->
                        String.format(
                                "Composite PK is %d bytes, over the InnoDB utf8mb4 limit of %d"
                                        + " bytes",
                                compositePkBytes, INNODB_UTF8MB4_INDEX_LIMIT_BYTES));
    }

    private static int varcharLength(String ddl, String columnName) {
        Matcher matcher =
                Pattern.compile("(?i)\\b" + Pattern.quote(columnName) + "\\s+VARCHAR\\((\\d+)\\)")
                        .matcher(ddl);
        if (!matcher.find()) {
            throw new IllegalStateException("Missing VARCHAR definition for " + columnName);
        }
        return Integer.parseInt(matcher.group(1));
    }
}
