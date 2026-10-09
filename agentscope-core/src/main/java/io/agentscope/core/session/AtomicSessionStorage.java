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

import java.util.List;

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

    /** Optional read-only discovery of object paths under a prefix. Recovery never uses listing. */
    default List<String> listPaths(String prefix) {
        throw new UnsupportedOperationException("This session storage does not support discovery");
    }

    /** expectedVersion=0 means create if absent. Unsupported backends must throw. */
    boolean compareAndSet(String path, long expectedVersion, byte[] bytes);
}
