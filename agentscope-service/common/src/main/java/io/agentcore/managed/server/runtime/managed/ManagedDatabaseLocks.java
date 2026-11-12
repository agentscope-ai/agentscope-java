/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

/** Database-specific nonblocking claims for background aggregation. */
final class ManagedDatabaseLocks {
    private ManagedDatabaseLocks() {}

    static String skipLocked(DataSource source) {
        return lock(source, " FOR UPDATE SKIP LOCKED");
    }

    static String execution(DataSource source) {
        return lock(source, " FOR NO KEY UPDATE");
    }

    private static String lock(DataSource source, String postgresLock) {
        try {
            String product =
                    JdbcUtils.extractDatabaseMetaData(
                            source, metadata -> metadata.getDatabaseProductName());
            // Embedded fixtures use a blocking claim; production PostgreSQL skips busy rows.
            return "PostgreSQL".equals(product) ? postgresLock : " FOR UPDATE";
        } catch (MetaDataAccessException unavailable) {
            throw new IllegalStateException(
                    "Cannot determine Managed database lock mode", unavailable);
        }
    }
}
