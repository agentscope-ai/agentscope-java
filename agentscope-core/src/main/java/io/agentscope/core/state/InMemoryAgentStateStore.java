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
package io.agentscope.core.state;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of the {@link AgentStateStore} interface.
 *
 * <p>This implementation stores agent state in memory using a {@link ConcurrentHashMap}. It is
 * suitable for single-process applications where persistence across restarts is not required.
 *
 * <p><b>Slot key:</b> the {@code (userId, sessionId)} pair is the storage slot. {@code null}
 * userId is mapped to the literal {@code "__anon__"} bucket so anonymous sessions don't
 * collide with users actually named {@code ""}.
 *
 * <p><b>Thread Safety:</b> this class is thread-safe.
 *
 * <p><b>Limitations:</b>
 *
 * <ul>
 *   <li>State is lost when the JVM exits
 *   <li>Not suitable for distributed environments
 *   <li>Memory usage grows with the number of sessions
 * </ul>
 */
public class InMemoryAgentStateStore implements AgentStateStore {

    /** Sentinel namespace for callers that pass {@code userId == null}. */
    private static final String ANON_USER = "__anon__";

    /** users → (sessionId → SessionData) */
    private final Map<String, Map<String, SessionData>> users = new ConcurrentHashMap<>();

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        SessionData data = lookupOrCreate(userId, sessionId);
        data.setSingleState(key, value);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        SessionData data = lookupOrCreate(userId, sessionId);
        data.setListState(key, values);
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        SessionData data = lookup(userId, sessionId);
        if (data == null) {
            return Optional.empty();
        }
        State state = data.getSingleState(key);
        if (state == null) {
            return Optional.empty();
        }
        if (!type.isInstance(state)) {
            throw new ClassCastException(
                    "State for key '"
                            + key
                            + "' is of type "
                            + state.getClass().getName()
                            + ", expected "
                            + type.getName());
        }
        return Optional.of(type.cast(state));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        SessionData data = lookup(userId, sessionId);
        if (data == null) {
            return List.of();
        }
        List<? extends State> list = data.getListState(key);
        if (list == null) {
            return List.of();
        }
        return (List<T>) list;
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return lookup(userId, sessionId) != null;
    }

    @Override
    public void delete(String userId, String sessionId) {
        Map<String, SessionData> userBucket = users.get(normalizeUser(userId));
        if (userBucket != null) {
            userBucket.remove(requireSessionId(sessionId));
        }
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        SessionData data = lookup(userId, sessionId);
        if (data != null) {
            data.removeSingleState(key);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        Map<String, SessionData> userBucket = users.get(normalizeUser(userId));
        if (userBucket == null) {
            return Set.of();
        }
        return new HashSet<>(userBucket.keySet());
    }

    /** Total number of {@code (userId, sessionId)} slots currently stored. */
    public int getSessionCount() {
        return users.values().stream().mapToInt(Map::size).sum();
    }

    /** Wipe everything (useful for testing). */
    public void clearAll() {
        users.clear();
    }

    private SessionData lookupOrCreate(String userId, String sessionId) {
        return users.computeIfAbsent(normalizeUser(userId), u -> new ConcurrentHashMap<>())
                .computeIfAbsent(requireSessionId(sessionId), s -> new SessionData());
    }

    private SessionData lookup(String userId, String sessionId) {
        Map<String, SessionData> userBucket = users.get(normalizeUser(userId));
        if (userBucket == null) {
            return null;
        }
        return userBucket.get(requireSessionId(sessionId));
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static String requireSessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId;
    }

    /** Internal class to hold session data. */
    private static class SessionData {
        private final Map<String, State> singleStates = new ConcurrentHashMap<>();
        private final Map<String, List<State>> listStates = new ConcurrentHashMap<>();

        void setSingleState(String key, State value) {
            singleStates.put(key, value);
        }

        State getSingleState(String key) {
            return singleStates.get(key);
        }

        void removeSingleState(String key) {
            singleStates.remove(key);
        }

        void setListState(String key, List<? extends State> values) {
            listStates.put(key, List.copyOf(values));
        }

        List<? extends State> getListState(String key) {
            return listStates.get(key);
        }
    }
}
