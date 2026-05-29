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
package io.agentscope.builder.runtime.marketplace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.config.MarketplaceConfigEntry;
import io.agentscope.builder.web.persistence.jpa.UserMarketplaceEntity;
import io.agentscope.builder.web.persistence.jpa.UserMarketplaceRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * JPA-backed equivalent of claw's {@code MarketplacePersistence}. Persists each user's marketplace
 * set as one row in {@code builder_user_marketplace} keyed by {@code (user_id, marketplace_id)}.
 * Does <em>not</em> touch {@code agentscope.json} — builder treats this as platform state, not as
 * single-user config.
 *
 * <p>All mutations are bracketed in a Spring-managed transaction and, on success, drive
 * {@link UserMarketplaceRegistry#reload(String, String, MarketplaceConfigEntry)} or
 * {@link UserMarketplaceRegistry#unregister(String, String)} so the live in-memory registry stays
 * consistent with the database without a restart.
 */
@Component
public class UserMarketplacePersistence {

    private static final Logger log = LoggerFactory.getLogger(UserMarketplacePersistence.class);

    private final UserMarketplaceRepository repository;
    private final UserMarketplaceRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserMarketplacePersistence(
            UserMarketplaceRepository repository, @Lazy UserMarketplaceRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    /**
     * Load every marketplace owned by {@code userId} as a {@code (id → entry)} map, ordered by id.
     * The map is suitable for direct hydration into {@link UserMarketplaceRegistry}.
     */
    public Map<String, MarketplaceConfigEntry> loadAllForUser(String userId) {
        List<UserMarketplaceEntity> rows = repository.findByUserIdOrderByMarketplaceIdAsc(userId);
        Map<String, MarketplaceConfigEntry> out = new LinkedHashMap<>();
        for (UserMarketplaceEntity row : rows) {
            try {
                out.put(row.getMarketplaceId(), toEntry(row));
            } catch (RuntimeException ex) {
                log.warn(
                        "Skipping malformed marketplace row id={} for user='{}': {}",
                        row.getId(),
                        userId,
                        ex.getMessage());
            }
        }
        return out;
    }

    /** Load one marketplace by id for {@code userId}, if it exists. */
    public Optional<MarketplaceConfigEntry> load(String userId, String marketplaceId) {
        return repository.findByUserIdAndMarketplaceId(userId, marketplaceId).map(this::toEntry);
    }

    /** Whether {@code userId} owns a marketplace with the given id. */
    public boolean exists(String userId, String marketplaceId) {
        return repository.existsByUserIdAndMarketplaceId(userId, marketplaceId);
    }

    /**
     * Insert a new marketplace row for {@code userId}. The caller is responsible for ensuring the
     * id does not already exist — use {@link #exists(String, String)} first to surface a 409 to
     * the user.
     */
    @Transactional
    public void insert(String userId, String marketplaceId, MarketplaceConfigEntry entry) {
        UserMarketplaceEntity row =
                new UserMarketplaceEntity(
                        userId, marketplaceId, entry.getType(), writePropertiesJson(entry));
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        registry.reload(userId, marketplaceId, entry);
    }

    /** Replace the existing row for {@code (userId, marketplaceId)}. */
    @Transactional
    public void update(String userId, String marketplaceId, MarketplaceConfigEntry entry) {
        UserMarketplaceEntity row =
                repository
                        .findByUserIdAndMarketplaceId(userId, marketplaceId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Marketplace not found: " + marketplaceId));
        row.setType(entry.getType());
        row.setPropertiesJson(writePropertiesJson(entry));
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        registry.reload(userId, marketplaceId, entry);
    }

    /** Remove the row and unregister the live instance. No-op if the row is already gone. */
    @Transactional
    public boolean delete(String userId, String marketplaceId) {
        if (!repository.existsByUserIdAndMarketplaceId(userId, marketplaceId)) {
            return false;
        }
        repository.deleteByUserIdAndMarketplaceId(userId, marketplaceId);
        registry.unregister(userId, marketplaceId);
        return true;
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private MarketplaceConfigEntry toEntry(UserMarketplaceEntity row) {
        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType(row.getType());
        Map<String, Object> props = readPropertiesJson(row.getPropertiesJson());
        if (props != null) {
            for (Map.Entry<String, Object> e : props.entrySet()) {
                entry.setProperty(e.getKey(), e.getValue());
            }
        }
        return entry;
    }

    private String writePropertiesJson(MarketplaceConfigEntry entry) {
        Map<String, Object> props =
                entry.getProperties() != null ? entry.getProperties() : Map.of();
        try {
            return mapper.writeValueAsString(props);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot serialise marketplace properties: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> readPropertiesJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot deserialise marketplace properties: " + e.getMessage(), e);
        }
    }
}
