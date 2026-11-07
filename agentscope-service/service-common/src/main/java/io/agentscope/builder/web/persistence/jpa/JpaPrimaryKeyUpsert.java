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
package io.agentscope.builder.web.persistence.jpa;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;

/** Inserts a row or locks its primary key without overwriting the existing values. */
final class JpaPrimaryKeyUpsert {
    private JpaPrimaryKeyUpsert() {}

    static void execute(
            EntityManager entityManager,
            Class<?> entityType,
            String key,
            String columns,
            Object... values) {
        var factory =
                entityManager.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class);
        var mapping =
                (AbstractEntityPersister)
                        factory.getMappingMetamodel().getEntityDescriptor(entityType);
        String table = mapping.getTableName();
        Dialect dialect = factory.getJdbcServices().getDialect();
        var parameters = new ArrayList<String>();
        for (int i = 0; i < values.length; i++) parameters.add(":v" + i);
        String bindings = String.join(", ", parameters);
        String insert = "insert into " + table + " (" + columns + ") values (" + bindings + ")";
        String sql;
        if (dialect instanceof PostgreSQLDialect) {
            sql = insert + " on conflict (" + key + ") do update set " + key + " = excluded." + key;
        } else if (dialect instanceof MySQLDialect) {
            // Callers use the PRIMARY key only. A secondary unique-key conflict would retain
            // a next-key lock across unrelated event IDs, even at READ_COMMITTED.
            sql = insert + " on duplicate key update " + key + " = " + key;
        } else if (dialect instanceof H2Dialect) {
            if (columns.equals(key)) {
                sql =
                        "merge into "
                                + table
                                + " ("
                                + key
                                + ") key ("
                                + key
                                + ") values ("
                                + bindings
                                + ")";
            } else {
                var sourceColumns = new ArrayList<String>();
                for (String column : columns.split(",")) sourceColumns.add("v." + column.trim());
                sql =
                        "merge into "
                                + table
                                + " t using (values ("
                                + bindings
                                + ")) v ("
                                + columns
                                + ") on t."
                                + key
                                + " = v."
                                + key
                                + " when matched then update set "
                                + key
                                + " = t."
                                + key
                                + " when not matched then insert ("
                                + columns
                                + ") values ("
                                + String.join(", ", sourceColumns)
                                + ")";
            }
        } else {
            throw new IllegalStateException("Unsupported event log dialect: " + dialect);
        }
        var query = entityManager.createNativeQuery(sql);
        for (int i = 0; i < values.length; i++) query.setParameter("v" + i, values[i]);
        query.executeUpdate();
    }
}
