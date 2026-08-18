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

/**
 * JDBC dialect layer: table-domain interfaces (table) + aggregate abstract class + vendor
 * implementations (vendor).
 *
 * <p>This package is the assembly layer. {@link io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect}
 * is the aggregate abstract class that implements all table-domain interfaces and the
 * {@link io.agentscope.extensions.jdbc.dialect.SandboxLockStrategy} contract. It holds the
 * unified table prefix with per-table overrides. {@link io.agentscope.extensions.jdbc.dialect.BoundSql}
 * is the unified return type for business SQL ("SQL + bind params").
 * {@link io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialectBuilder} handles SPI-based
 * auto-detection and optional table creation.
 *
 * <pre>
 * (1) dialect.table: one interface per table (abstract DDL + ANSI business SQL defaults), methods prefixed with table short name
 *
 *   +--------------------+   +------------------------+   +--------------------+
 *   | StoreDialect       |   | SessionStateDialect    |   | SnapshotDialect    |
 *   | DDL (abstract)     |   | DDL (abstract)         |   | DDL (abstract)     |
 *   | ANSI SQL (default) |   | ANSI SQL (default)     |   | ANSI SQL (default) |
 *   | base name "store"  |   | base name "sessions"   |   | base name "snaps"  |
 *   +--------+-----------+   +----------+-------------+   +--------+-----------+
 *            |                          |                          |
 *            v                          v                          v             <- implements (aggregate implements all table-domain interfaces + SandboxLockStrategy)
 *   +--------+--------------------------+--------------------------+-----------+
 *   | AbstractJdbcDialect  aggregate abstract class                                |
 *   | Implements StoreDialect, SessionStateDialect, SnapshotDialect, SandboxLockStrategy |
 *   | Holds tablePrefix + per-table overrides; from(DataSource) returns builder     |
 *   | build() detects DB -> binds DataSource -> assembles names -> auto-creates tables |
 *   | createTableDdls() collects all DDL; tryEnter() default = table-based lock       |
 *   | Final name resolution: override > prefix + base                               |
 *   +--------+--------------------------+--------------------------+-----------+
 *            |                          |                          |
 *            v                          v                          v             <- extends (one class per database — ALL differences in one file)
 *
 * (2) dialect.vendor: override only DDL + divergent SQL + lock (if native), inherit ANSI defaults
 *
 *   +----------------------+   +----------------------+   +------------------+   +------------------+
 *   | MysqlDialect         |   | PostgresDialect      |   | H2Dialect        |   | SqliteDialect    |
 *   | ON DUPLICATE KEY     |   | ON CONFLICT          |   | MERGE INTO       |   | ON CONFLICT      |
 *   | LONGTEXT/LONGBLOB    |   | TEXT/BYTEA           |   | CLOB/BLOB        |   | TEXT/BLOB        |
 *   | tryEnter: GET_LOCK   |   | tryEnter: inherited  |   | tryEnter: inherited | | tryEnter: inherited |
 *   +----------------------+   +----------------------+   +------------------+   +------------------+
 *
 * (3) Adding a database with native locks = override tryEnter() in the vendor class (no separate strategy class)
 * </pre>
 *
 * <h2>Adding a new table</h2>
 * <ol>
 *   <li>Create a table-domain interface in {@code table} (methods prefixed with the table short name).</li>
 *   <li>Add it to the aggregate's {@code implements} clause, plus name-override field + final resolver
 *       + builder method + add a line to {@code createTableDdls()}.</li>
 *   <li>Override the abstract DDL in each vendor class.</li>
 * </ol>
 *
 * <h2>Adding a new database</h2>
 * <ol>
 *   <li>Create a vendor class extending
 *       {@link io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect}.</li>
 *   <li>Override DDL, divergent SQL, and {@code supports()}. Override {@code tryEnter()}
 *       only if the database has native advisory locks.</li>
 *   <li>Register the class in
 *       {@code META-INF/services/io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect}.</li>
 * </ol>
 */
package io.agentscope.extensions.jdbc.dialect;
