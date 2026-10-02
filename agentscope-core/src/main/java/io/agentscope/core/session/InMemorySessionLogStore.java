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
