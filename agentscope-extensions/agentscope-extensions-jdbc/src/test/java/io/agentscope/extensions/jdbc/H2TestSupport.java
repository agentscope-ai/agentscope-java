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

import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * Test support class that creates an H2 in-memory DataSource for integration tests.
 *
 * <p>Each call creates a fresh database with a unique name to ensure complete test
 * isolation — no data leaks between tests even within the same class.
 *
 * @author shanhongyu
 */
public final class H2TestSupport {

    private H2TestSupport() {}

    /**
     * Creates a fresh H2 in-memory data source with a unique database name.
     *
     * @param dbName a base name for the database (a UUID suffix is appended)
     * @return a live DataSource
     */
    public static DataSource createDataSource(String dbName) {
        String uniqueName =
                dbName + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:" + uniqueName + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }
}
