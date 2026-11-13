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

import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.Wakeups;
import java.nio.file.Path;
import java.util.Objects;

/** Complete embedded backend: persistent SQLite records and process-local notifications. */
public final class LocalBackend {
    private LocalBackend() {}

    /**
     * Opens or creates directory/durable.db, preserving the same sessions across restarts.
     * Close the returned backend after its runtimes/observers. No external services are required.
     */
    public static SessionBackend open(Path directory) {
        var path = Objects.requireNonNull(directory);
        return new TransactionalSessionBackend(
                        JdbcRecordStore.sqlite(path.resolve("durable.db")),
                        new FileObjectStore(path.resolve("objects")))
                .withWakeups(Wakeups.local());
    }
}
