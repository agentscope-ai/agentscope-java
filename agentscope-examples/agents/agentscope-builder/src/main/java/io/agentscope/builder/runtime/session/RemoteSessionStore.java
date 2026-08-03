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
package io.agentscope.builder.runtime.session;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Fail-fast {@link SessionStore} placeholder for distributed deployments.
 *
 * <p>This class intentionally rejects mutations until a concrete remote backend is wired. It
 * prevents the builder runtime from silently dropping session metadata when the rest of the runtime
 * is configured for distributed storage.
 */
public final class RemoteSessionStore implements SessionStore {

    @Override
    public void load() {}

    @Override
    public void save(SessionEntry entry) {
        throw unsupported();
    }

    @Override
    public void touch(String sessionKey, long lastActivityMs) {
        throw unsupported();
    }

    @Override
    public void remove(String sessionKey) {
        throw unsupported();
    }

    @Override
    public Collection<StoredEntry> listAll() {
        return List.of();
    }

    @Override
    public Optional<StoredEntry> get(String sessionKey) {
        return Optional.empty();
    }

    @Override
    public int size() {
        return 0;
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "RemoteSessionStore is a placeholder; provide a concrete SessionStore "
                        + "implementation backed by shared storage.");
    }
}
