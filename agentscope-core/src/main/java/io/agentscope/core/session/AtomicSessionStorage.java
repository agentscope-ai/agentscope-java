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
package io.agentscope.core.session;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Narrow storage capability. CAS must be atomic across all writers sharing this namespace.
 * Missing is null; read/write failures throw. Implementations must durably store bytes before ACK.
 * The runtime journal storage must not be writable through model filesystem tools.
 */
public interface AtomicSessionStorage {
    record Value(long version, byte[] bytes) {
        public Value {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    Value read(String path);

    /** Reads several objects in one round-trip when the backend can; entries for paths that do
     * not exist are simply absent from the result. The default falls back to per-path reads.
     * Backends whose per-read cost is dominated by connection setup (SQLite over JDBC, for
     * example) should override this so a journal replay costs one round-trip, not one per
     * segment. */
    default Map<String, Value> readAll(Collection<String> paths) {
        var result = new HashMap<String, Value>();
        for (String path : paths) {
            var value = read(path);
            if (value != null) result.put(path, value);
        }
        return result;
    }

    /** Optional read-only discovery of object paths under a prefix. Recovery never uses listing. */
    default List<String> listPaths(String prefix) {
        throw new UnsupportedOperationException("This session storage does not support discovery");
    }

    /** expectedVersion=0 means create if absent. Unsupported backends must throw. */
    boolean compareAndSet(String path, long expectedVersion, byte[] bytes);
}
