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
