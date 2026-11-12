/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Regression guard: object IO must not run while a SQL transaction holds row locks. */
final class UnlockedRuntimeObjectStore implements RuntimeObjectStore {
    private final RuntimeObjectStore delegate;

    UnlockedRuntimeObjectStore(RuntimeObjectStore delegate) {
        this.delegate = delegate;
    }

    public String backendId() {
        return delegate.backendId();
    }

    public Reference put(String scope, String kind, String key, byte[] content) {
        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive(),
                "Object PUT inside SQL transaction: " + kind);
        return delegate.put(scope, kind, key, content);
    }

    public byte[] read(String scope, String kind, String key) {
        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive(),
                "Object GET inside SQL transaction: " + kind);
        return delegate.read(scope, kind, key);
    }

    public boolean exists(String scope, String kind, String key) {
        return delegate.exists(scope, kind, key);
    }
}
