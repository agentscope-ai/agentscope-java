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
package io.agentscope.builder.web.persistence.jpa;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed {@link AgentStateStore} for agentscope-builder.
 *
 * <p>Uses the same Spring {@code DataSource} / JPA schema as the rest of the builder catalog
 * (H2 file by default, MySQL/PostgreSQL via the {@code jdbc} profile), so short-term brain state
 * is shared across replicas without Redis.
 *
 * <p>Registered as a Spring bean via {@code DataPlaneConfig#agentStateStore} on the data
 * plane; this class itself is not a
 * {@code @Component} so tests can supply an alternate {@link AgentStateStore}.
 */
@Transactional
public class JpaAgentStateStore implements AgentStateStore {

    private static final String ANON_USER = "__anon__";

    private final AgentStateEntityRepository repository;
    private final DeletedSessionRegistry deletedSessions;
    private final JsonCodec codec;
    private final ObjectMapper mapper;

    public JpaAgentStateStore(
            AgentStateEntityRepository repository, DeletedSessionRegistry deletedSessions) {
        this.repository = repository;
        this.deletedSessions = deletedSessions;
        this.codec = JsonUtils.getJsonCodec();
        if (!(codec instanceof JacksonJsonCodec jackson)) {
            throw new IllegalStateException(
                    "JpaAgentStateStore requires JacksonJsonCodec for list deserialization");
        }
        this.mapper = jackson.getObjectMapper();
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        validate(sessionId, key);
        try {
            upsert(userId, sessionId, key, false, codec.toJson(value));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to save agent state: " + key, ex);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        validate(sessionId, key);
        try {
            upsert(userId, sessionId, key, true, codec.toJson(values == null ? List.of() : values));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to save agent state list: " + key, ex);
        }
    }

    /**
     * Overridden only so that a transaction is open while the row is read.
     *
     * <p>Inherited as an interface default method it would be declared by {@link AgentStateStore},
     * so Spring finds no transaction attribute for it and the class-level {@link Transactional} never
     * applies; its call to {@link #get} is then a self-invocation that bypasses the proxy too. With
     * no transaction, reading the PostgreSQL large object behind {@code state_data} fails and every
     * turn would start from an empty context.
     */
    @Override
    @Transactional(readOnly = true)
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return new VersionedState<>(get(userId, sessionId, key, type).orElse(null), UNVERSIONED);
    }

    @Override
    @Transactional(readOnly = true)
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        validate(sessionId, key);
        return repository
                .findByUserIdAndSessionIdAndStateKey(normalizeUser(userId), sessionId, key)
                .filter(e -> !e.isListKind())
                .map(
                        e -> {
                            try {
                                return codec.fromJson(e.getStateData(), type);
                            } catch (Exception ex) {
                                throw new RuntimeException(
                                        "Failed to deserialize agent state: " + key, ex);
                            }
                        });
    }

    @Override
    @Transactional(readOnly = true)
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        validate(sessionId, key);
        Optional<AgentStateEntity> row =
                repository.findByUserIdAndSessionIdAndStateKey(
                        normalizeUser(userId), sessionId, key);
        if (row.isEmpty() || !row.get().isListKind()) {
            return List.of();
        }
        try {
            JavaType listType =
                    mapper.getTypeFactory().constructCollectionType(List.class, itemType);
            List<T> values = mapper.readValue(row.get().getStateData(), listType);
            return values == null ? List.of() : values;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to deserialize agent state list: " + key, ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return repository.existsByUserIdAndSessionId(normalizeUser(userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        repository.deleteByUserIdAndSessionId(normalizeUser(userId), sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        validate(sessionId, key);
        repository.deleteByUserIdAndSessionIdAndStateKey(normalizeUser(userId), sessionId, key);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> listSessionIds(String userId) {
        List<String> ids = repository.findDistinctSessionIdsByUserId(normalizeUser(userId));
        return ids == null || ids.isEmpty() ? Collections.emptySet() : new LinkedHashSet<>(ids);
    }

    private void upsert(
            String userId, String sessionId, String key, boolean listKind, String json) {
        // A turn that is unwinding after its session was deleted still saves state,
        // which would restore the row teardown just dropped.
        if (deletedSessions.isDeleted(sessionId)) {
            return;
        }
        String uid = normalizeUser(userId);
        long now = System.currentTimeMillis();
        AgentStateEntity entity =
                repository
                        .findByUserIdAndSessionIdAndStateKey(uid, sessionId, key)
                        .orElseGet(AgentStateEntity::new);
        entity.setUserId(uid);
        entity.setSessionId(sessionId);
        entity.setStateKey(key);
        entity.setListKind(listKind);
        entity.setStateData(json);
        entity.setUpdatedAt(now);
        repository.save(entity);
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static void validate(String sessionId, String key) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
