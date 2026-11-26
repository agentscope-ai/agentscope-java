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

import io.agentscope.durable.DurableException;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.Wakeups;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Complete embedded backend: persistent local records and process-local notifications. */
public final class LocalBackend {
    private LocalBackend() {}

    /** Opens a local directory. New directories use files. Existing SQLite directories reopen in place. */
    public static SessionBackend open(Path directory) {
        return open(directory, false);
    }

    /** Explicitly selects SQLite for a new or existing SQLite directory. */
    public static SessionBackend sqlite(Path directory) {
        return open(directory, true);
    }

    private static synchronized SessionBackend open(Path directory, boolean sqlite) {
        try {
            Files.createDirectories(directory);
            try (var channel =
                            FileChannel.open(
                                    directory.resolve("backend.lock"),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                var database = directory.resolve("durable.db");
                var records = directory.resolve("records");
                boolean useDatabase = sqlite || Files.exists(database);
                if (useDatabase && Files.exists(records))
                    throw new IllegalArgumentException("Directory already contains file records");
                var store =
                        useDatabase
                                ? JdbcRecordStore.sqlite(database)
                                : new FileRecordStore(records);
                return new TransactionalSessionBackend(
                                store, new FileObjectStore(directory.resolve("objects")))
                        .withWakeups(Wakeups.local());
            }
        } catch (IOException error) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "Cannot open local backend", error);
        }
    }
}
