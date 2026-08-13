/**
 * Table-domain dialect interfaces — one per table.
 *
 * <p>Each interface defines the abstract create-table DDL and ANSI default business SQL
 * (returning {@link io.agentscope.extensions.jdbc.dialect.BoundSql}) for one table.
 * Method names are prefixed with the table short name (e.g. {@code storeUpsert},
 * {@code sessionStateSelect}) to avoid collision when the aggregate class implements
 * all interfaces.
 *
 * <p>The {@code xxxTableName()} default returns only the base name (without prefix); the
 * final resolved name (prefix + base, or per-table override) is produced by the aggregate
 * class's final resolver.
 */
package io.agentscope.extensions.jdbc.dialect.table;
