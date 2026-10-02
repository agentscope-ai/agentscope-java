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
package io.agentscope.dataagent.runtime.marketplace;

import io.agentscope.dataagent.runtime.config.MarketplaceConfigEntry;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Live registry of per-user {@link DataAgentMarketplace} instances, keyed by {@code userId} →
 * marketplace id.
 *
 * <p>Marketplaces in builder are user-private: there is no admin-curated platform tier. Two
 * different users may reuse the same marketplace id without collision and never see each other's
 * entries. The registry mirrors claw's {@code ClawMarketplaceRegistry} but with the outer user-id
 * dimension and no startup pre-load — instances are built on demand because the user population
 * can be large.
 *
 * <p>Lifecycle: {@code UserMarketplacePersistence} drives mutations through
 * {@link #reload(String, String, MarketplaceConfigEntry)} and {@link #unregister(String, String)}
 * after writing the row. The registry owns {@link DataAgentMarketplace#close()} for replaced or
 * removed instances so the previous git clone / nacos client is released eagerly.
 */
@Component
public class UserMarketplaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserMarketplaceRegistry.class);

    private final WorkspaceManagerFactory workspaceFactory;
    private final UserMarketplacePersistence persistence;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, DataAgentMarketplace>>
            byUser = new ConcurrentHashMap<>();
    private final Map<String, DataAgentMarketplaceFactory> factories;

    public UserMarketplaceRegistry(
            WorkspaceManagerFactory workspaceFactory,
            UserMarketplacePersistence persistence,
            List<DataAgentMarketplaceFactoryRegistration> registrations) {
        this.workspaceFactory = workspaceFactory;
        this.persistence = persistence;
        Map<String, DataAgentMarketplaceFactory> map = new java.util.HashMap<>();
        for (DataAgentMarketplaceFactoryRegistration r : registrations) {
            map.put(r.type().toLowerCase(Locale.ROOT), r.factory());
        }
        this.factories = Map.copyOf(map);
    }

    /**
     * Spring-injectable registration of a {@link DataAgentMarketplaceFactory} for a given type
     * discriminator. Submit a {@code @Bean} returning this record from any configuration class
     * to make the corresponding marketplace type available to the registry.
     */
    public record DataAgentMarketplaceFactoryRegistration(
            String type, DataAgentMarketplaceFactory factory) {}

    /**
     * Snapshot of every live marketplace for {@code userId}, ordered by id. First call for a user
     * lazily hydrates the per-user map from the database.
     */
    public List<DataAgentMarketplace> list(String userId) {
        ConcurrentHashMap<String, DataAgentMarketplace> map = ensureLoaded(userId);
        List<DataAgentMarketplace> snapshot = new ArrayList<>(map.values());
        snapshot.sort(Comparator.comparing(DataAgentMarketplace::id));
        return snapshot;
    }

    /** Returns {@code userId}'s marketplace with the given id, if registered. */
    public Optional<DataAgentMarketplace> find(String userId, String id) {
        if (userId == null || id == null) return Optional.empty();
        ConcurrentHashMap<String, DataAgentMarketplace> map = ensureLoaded(userId);
        return Optional.ofNullable(map.get(id));
    }

    /** Whether {@code userId} owns a marketplace with the given id. */
    public boolean contains(String userId, String id) {
        if (userId == null || id == null) return false;
        return ensureLoaded(userId).containsKey(id);
    }

    /**
     * Replace (or first-time install) {@code userId}'s marketplace at {@code id} with one built
     * from {@code entry}. The previously registered instance, if any, is closed after the new one
     * is in place.
     */
    public DataAgentMarketplace reload(String userId, String id, MarketplaceConfigEntry entry) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entry, "entry");
        DataAgentMarketplace next = build(userId, id, entry);
        ConcurrentHashMap<String, DataAgentMarketplace> map = ensureLoaded(userId);
        DataAgentMarketplace previous = map.put(id, next);
        closeQuietly(previous);
        return next;
    }

    /** Remove and close {@code userId}'s marketplace at {@code id}. No-op if not registered. */
    public boolean unregister(String userId, String id) {
        if (userId == null || id == null) return false;
        ConcurrentHashMap<String, DataAgentMarketplace> map = byUser.get(userId);
        if (map == null) return false;
        DataAgentMarketplace removed = map.remove(id);
        if (removed == null) return false;
        closeQuietly(removed);
        return true;
    }

    /**
     * Build a marketplace instance from a config entry without registering it. Used by
     * {@code MarketplacesController#testTransient} so a connection probe runs against the same
     * code path a real registration would use, but without taking the (id) slot if the probe
     * fails.
     *
     * @throws IllegalArgumentException if {@code entry.type} is unknown or required fields are
     *     missing
     */
    public DataAgentMarketplace build(String userId, String id, MarketplaceConfigEntry entry) {
        if (entry.getType() == null || entry.getType().isBlank()) {
            throw new IllegalArgumentException("marketplace '" + id + "' has no type");
        }
        String type = entry.getType().toLowerCase(Locale.ROOT);
        Map<String, Object> props =
                entry.getProperties() != null ? entry.getProperties() : Map.of();
        DataAgentMarketplaceFactory factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "unsupported marketplace type '"
                            + entry.getType()
                            + "' for '"
                            + id
                            + "' — register a "
                            + DataAgentMarketplaceFactory.class.getSimpleName()
                            + " bean for this type");
        }
        return factory.create(userId, id, props, workspaceFactory);
    }

    /**
     * SPI for plugging in marketplace stores. v1 ships
     * {@link io.agentscope.dataagent.runtime.marketplace.LocalApprovalMarketplace} under the
     * {@code "local"} type; git and nacos stores are intentionally not bundled (lift the
     * {@code GitDataAgentMarketplace} / {@code NacosDataAgentMarketplace} classes from
     * agentscope-builder if you need them).
     */
    @FunctionalInterface
    public interface DataAgentMarketplaceFactory {
        DataAgentMarketplace create(
                String userId, String id, Map<String, Object> props, WorkspaceManagerFactory wsf);
    }

    /** Close every marketplace; used during shutdown so we don't leak git clones / clients. */
    @PreDestroy
    public void closeAll() {
        Collection<ConcurrentHashMap<String, DataAgentMarketplace>> snapshot =
                new ArrayList<>(byUser.values());
        byUser.clear();
        for (ConcurrentHashMap<String, DataAgentMarketplace> map : snapshot) {
            for (DataAgentMarketplace mp : map.values()) {
                closeQuietly(mp);
            }
        }
    }

    private ConcurrentHashMap<String, DataAgentMarketplace> ensureLoaded(String userId) {
        return byUser.computeIfAbsent(userId, this::hydrateFromStore);
    }

    private ConcurrentHashMap<String, DataAgentMarketplace> hydrateFromStore(String userId) {
        ConcurrentHashMap<String, DataAgentMarketplace> map = new ConcurrentHashMap<>();
        for (Map.Entry<String, MarketplaceConfigEntry> e :
                persistence.loadAllForUser(userId).entrySet()) {
            try {
                map.put(e.getKey(), build(userId, e.getKey(), e.getValue()));
            } catch (RuntimeException ex) {
                log.warn(
                        "Failed to initialise marketplace '{}' for user '{}': {}",
                        e.getKey(),
                        userId,
                        ex.getMessage(),
                        ex);
            }
        }
        return map;
    }

    private void closeQuietly(DataAgentMarketplace mp) {
        if (mp == null) return;
        try {
            mp.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close marketplace '{}': {}", mp.id(), e.getMessage(), e);
        }
    }
}
