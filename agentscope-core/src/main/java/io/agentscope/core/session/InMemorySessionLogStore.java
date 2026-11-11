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

import io.agentscope.core.agent.RuntimeContext;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit ephemeral backend for tests and non-persistent embedded applications. */
public final class InMemorySessionLogStore implements SessionLogStore {
    private final ConcurrentHashMap<SessionKey, AtomicSessionStorage> stores =
            new ConcurrentHashMap<>();

    @Override
    public SessionLog open(SessionKey key, RuntimeContext context) {
        return new JournalSessionLog(
                stores.computeIfAbsent(key, ignored -> new MemoryStorage()), key.storagePath());
    }

    @Override
    public List<SessionKey> list(RuntimeContext context) {
        String userId = context == null ? null : context.getUserId();
        return stores.entrySet().stream()
                .filter(entry -> Objects.equals(userId, entry.getKey().userId()))
                .filter(
                        entry ->
                                entry.getValue()
                                                        .read(
                                                                entry.getKey().storagePath()
                                                                        + "/session.json")
                                                != null
                                        || entry.getValue()
                                                        .read(
                                                                entry.getKey().storagePath()
                                                                        + "/inbox/session.json")
                                                != null)
                .map(entry -> entry.getKey())
                .sorted(
                        Comparator.comparing(SessionKey::agentId)
                                .thenComparing(SessionKey::sessionId))
                .toList();
    }

    private static final class MemoryStorage implements AtomicSessionStorage {
        private final ConcurrentHashMap<String, Value> values = new ConcurrentHashMap<>();

        @Override
        public Value read(String path) {
            return values.get(path);
        }

        @Override
        public synchronized boolean compareAndSet(String path, long expected, byte[] bytes) {
            var current = values.get(path);
            if ((current == null ? 0 : current.version()) != expected) return false;
            values.put(path, new Value(expected + 1, bytes));
            return true;
        }
    }
}
