/**
 * Vendor dialect implementations — one class per database.
 *
 * <p>Each class extends {@link io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect} and
 * overrides only the methods where its SQL diverges from ANSI defaults: create-table DDL,
 * UPSERT syntax, table-existence check, and optionally lock strategy. Business SQL that is
 * identical across databases is inherited from the table-domain interface defaults.
 */
package io.agentscope.extensions.jdbc.dialect.vendor;
