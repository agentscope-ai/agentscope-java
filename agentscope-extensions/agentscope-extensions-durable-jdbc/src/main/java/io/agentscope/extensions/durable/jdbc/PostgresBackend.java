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

package io.agentscope.extensions.durable.jdbc;

import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.Wakeups;
import io.agentscope.durable.store.ObjectStore;
import java.util.Objects;
import java.util.function.BiConsumer;
import javax.sql.DataSource;

/**
 * PostgreSQL backend assembly using a host-owned DataSource and optional notification transport.
 * Closing the backend returns its resources without closing the supplied pool or transport.
 */
public final class PostgresBackend {
    private PostgresBackend() {}

    public static Builder builder() {
        return new Builder();
    }

    /** Explicit deployment-time initialization; ordinary startup only checks the existing schema. */
    public static void initializeSchema(DataSource dataSource) {
        try (var store =
                new JdbcRecordStore(
                        Objects.requireNonNull(dataSource)::getConnection,
                        JdbcRecordStore.Dialect.POSTGRESQL,
                        true)) {
            // Schema creation completes in the constructor.
        }
    }

    public static final class Builder {
        private DataSource dataSource;
        private ObjectStore objects;
        private Wakeups notifications;
        private boolean initializeSchema;
        private BiConsumer<SessionKey, Throwable> errors =
                (session, error) ->
                        System.getLogger(PostgresBackend.class.getName())
                                .log(
                                        System.Logger.Level.WARNING,
                                        "Durable notification failed; polling remains active",
                                        error);

        /** Borrows a connection per operation. Schema selection belongs to the DataSource. */
        public Builder dataSource(DataSource value) {
            dataSource = Objects.requireNonNull(value);
            return this;
        }

        /** Borrowed transport, for example RocketMqWakeups; defaults to process-local hints. */
        public Builder notifications(Wakeups value) {
            notifications = Objects.requireNonNull(value);
            return this;
        }

        /** Opt in for development/provisioning identities with DDL permissions; defaults to false. */
        public Builder initializeSchema(boolean value) {
            initializeSchema = value;
            return this;
        }

        public Builder onNotificationError(BiConsumer<SessionKey, Throwable> value) {
            errors = Objects.requireNonNull(value);
            return this;
        }

        /** Borrowed immutable content store. Defaults to the transactional database store. */
        public Builder objects(ObjectStore value) {
            objects = Objects.requireNonNull(value);
            return this;
        }

        public SessionBackend build() {
            Objects.requireNonNull(dataSource, "dataSource");
            var records =
                    new JdbcRecordStore(
                            dataSource::getConnection,
                            JdbcRecordStore.Dialect.POSTGRESQL,
                            initializeSchema);
            var backend =
                    objects == null
                            ? new TransactionalSessionBackend(records)
                            : new TransactionalSessionBackend(records, objects);
            return backend.withWakeups(
                    notifications == null ? Wakeups.local() : notifications, errors);
        }
    }
}
