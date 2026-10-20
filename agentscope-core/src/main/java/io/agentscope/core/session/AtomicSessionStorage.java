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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

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

    /** A synchronous, callback-scoped view of storage reads. The view is not thread-safe and must
     * not escape the callback that receives it. */
    @FunctionalInterface
    interface ReadSession {
        /** Reads one stored value, or returns {@code null} when the path does not exist. */
        Value read(String path);
    }

    /**
     * Runs related reads in one backend-defined resource scope. The default preserves compatibility
     * by delegating each call to {@link #read(String)} and does not promise snapshot isolation.
     * Implementations may reuse a connection or another expensive read resource for the callback.
     *
     * @param operation synchronous reads to perform
     * @return the callback result
     * @param <T> callback result type
     */
    default <T> T withReadSession(Function<ReadSession, T> operation) {
        Objects.requireNonNull(operation, "operation");
        Thread owner = Thread.currentThread();
        var open = new AtomicBoolean(true);
        ReadSession session =
                path -> {
                    if (!open.get()) throw new IllegalStateException("Read session is closed");
                    if (Thread.currentThread() != owner)
                        throw new IllegalStateException("Read session cannot cross threads");
                    return read(path);
                };
        try {
            return operation.apply(session);
        } finally {
            open.set(false);
        }
    }

    /** Optional read-only discovery of object paths under a prefix. Recovery never uses listing. */
    default List<String> listPaths(String prefix) {
        throw new UnsupportedOperationException("This session storage does not support discovery");
    }

    /** expectedVersion=0 means create if absent. Unsupported backends must throw. */
    boolean compareAndSet(String path, long expectedVersion, byte[] bytes);
}
