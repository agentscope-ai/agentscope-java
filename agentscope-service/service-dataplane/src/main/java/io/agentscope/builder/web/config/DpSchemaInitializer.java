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
package io.agentscope.builder.web.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Ensures the dataplane JDBC schema {@code dp} exists before Hibernate validates / updates tables.
 * Safe on Postgres and H2; ignored when the driver rejects {@code CREATE SCHEMA}.
 */
@Component
public class DpSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DpSchemaInitializer.class);

    private final DataSource dataSource;

    public DpSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS dp");
            log.info("Ensured JDBC schema 'dp' exists");
        } catch (SQLException ex) {
            log.warn(
                    "Could not create schema 'dp' (may be unsupported by driver): {}",
                    ex.getMessage());
        }
    }
}
