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
 *   +--------------------+   +------------------------+   +--------------------+
 *   +--------------------+   +----------------------------+
 *   | SkillDialect       |   | SkillResourcesDialect      |     base table group: store/sessions/snapshots
 *   | base name "skills" |   | base name "skill_resources"|     skill table group: skills/skill_resources
 *   +--------------------+   +----------------------------+
 *            |                          |                          |
 *            v                          v                          v             &lt;- implements (aggregate implements all table-domain interfaces + SandboxLockStrategy)
 *   +--------+--------------------------+--------------------------+-----------+
 *   | AbstractJdbcDialect  aggregate abstract class                                |
 *   | Implements all table-domain interfaces + SandboxLockStrategy                |
 *   | Holds tablePrefix + per-table overrides + table-group flags; from(DataSource) returns builder |
 *   | build() detects DB -> binds DataSource -> assembles names/groups -> creates or validates tables |
 *   | createTableDdls() collects the enabled groups' DDL; tryEnter() default = table-based lock    |
 *   | Final name resolution: override > prefix + base                               |
 *   +--------+--------------------------+--------------------------+-----------+
 *            |                          |                          |
 *            v                          v                          v             &lt;- extends (one class per database — ALL differences in one file)
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
 *       + builder method + a gated line in {@code createTableDdls()}.</li>
 *   <li>Override the abstract DDL in each vendor class.</li>
 * </ol>
 *
 * <h2>Adding a new table group</h2>
 * <ol>
 *   <li>Follow "Adding a new table" for each table of the group.</li>
 *   <li>Add one {@code enableXxxTables(boolean)} builder switch (setter semantics, default off)
 *       and gate the group's lines in {@code createTableDdls()}.</li>
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
 *
 * <p>"Override DDL" means implementing <em>every</em> table domain's abstract create-table
 * method. Table domains added by framework upgrades arrive as new abstract methods on the
 * aggregate, so an out-of-tree vendor class fails to compile until it implements them —
 * deliberate: a dialect that cannot create a domain's tables must not assemble silently
 * behind a default that hides the gap. In-tree vendor classes are updated in the same
 * change, so only third-party dialects ever see that compile step.
 *
 * <p>On the runtime side, a third-party dialect compiled against the previous artifact is
 * not recompiled, and the JVM raises {@link AbstractMethodError} only when the missing
 * abstract method is actually invoked. The only invocation site sits inside the skill-group
 * gate of {@code createTableDdls()}, so an un-migrated dialect keeps serving the base
 * tables until somebody opts into the skill group — at which point it fails with an {@code
 * AbstractMethodError} on the skill DDL methods, the signal to recompile and implement the
 * skill domains.
 */
package io.agentscope.extensions.jdbc.dialect;
