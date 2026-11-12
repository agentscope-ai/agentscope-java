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
package io.agentscope.extensions.jdbc;

import java.sql.SQLException;
import java.util.Set;

/** Shared duplicate-key classification for JDBC stores and repositories. */
public final class JdbcConstraintErrors {

    // SQLState 23000 covers all integrity constraints; the vendor code identifies duplicates.
    // MySQL/MariaDB 1062 and 1022, Oracle ORA-00001, SQL Server 2601 and 2627.
    private static final Set<Integer> DUPLICATE_KEY_VENDOR_CODES =
            Set.of(1062, 1022, 1, 2601, 2627);
    // Informix/GBase 8s may report these duplicate codes without a SQLState.
    private static final Set<Integer> VENDOR_UNIQUE_VIOLATION_CODES = Set.of(-239, -268);

    private JdbcConstraintErrors() {}

    /**
     * Recognizes specific unique/primary-key violations, never the general integrity category.
     *
     * <p>SQLState 23505 identifies unique violations in PostgreSQL, H2 and compatible drivers.
     * SQLite's error 19 covers all constraints, so its optional driver is queried reflectively
     * for the precise result code. Unknown diagnostics or unavailable result codes return false;
     * callers must propagate those storage failures rather than treating them as contention.
     *
     * @param e the JDBC failure to classify
     * @return true only for a positively identified duplicate-key conflict
     */
    public static boolean isDuplicateKey(SQLException e) {
        if ("23505".equals(e.getSQLState())) {
            return true;
        }
        if ("23000".equals(e.getSQLState())
                && DUPLICATE_KEY_VENDOR_CODES.contains(e.getErrorCode())) {
            return true;
        }
        if (e.getErrorCode() == 19 && e.getClass().getName().startsWith("org.sqlite.")) {
            try {
                Object result = e.getClass().getMethod("getResultCode").invoke(e);
                if (result instanceof Enum<?> code) {
                    return "SQLITE_CONSTRAINT_PRIMARYKEY".equals(code.name())
                            || "SQLITE_CONSTRAINT_UNIQUE".equals(code.name());
                }
            } catch (ReflectiveOperationException ignored) {
                // An unknown constraint is not evidence of a duplicate key.
            }
        }
        return VENDOR_UNIQUE_VIOLATION_CODES.contains(e.getErrorCode());
    }
}
