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

import io.agentscope.builder.runtime.config.MarketplaceConfigEntry;
import io.agentscope.builder.web.workspace.SharedWorkspacePaths;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
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
 * Live registry of per-user {@link BuilderMarketplace} instances, keyed by {@code userId} →
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
 * after writing the row. The registry owns {@link BuilderMarketplace#close()} for replaced or
 * removed instances so the previous git clone / nacos client is released eagerly.
 */
@Component
public class UserMarketplaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserMarketplaceRegistry.class);

    private final SharedWorkspacePaths sharedWorkspacePaths;
    private final UserMarketplacePersistence persistence;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, BuilderMarketplace>> byUser =
            new ConcurrentHashMap<>();

    public UserMarketplaceRegistry(
            SharedWorkspacePaths sharedWorkspacePaths, UserMarketplacePersistence persistence) {
        this.sharedWorkspacePaths = sharedWorkspacePaths;
        this.persistence = persistence;
    }

    /**
     * Snapshot of every live marketplace for {@code userId}, ordered by id. First call for a user
     * lazily hydrates the per-user map from the database.
     */
    public List<BuilderMarketplace> list(String userId) {
        ConcurrentHashMap<String, BuilderMarketplace> map = ensureLoaded(userId);
        List<BuilderMarketplace> snapshot = new ArrayList<>(map.values());
        snapshot.sort(Comparator.comparing(BuilderMarketplace::id));
        return snapshot;
    }

    /** Returns {@code userId}'s marketplace with the given id, if registered. */
    public Optional<BuilderMarketplace> find(String userId, String id) {
        if (userId == null || id == null) return Optional.empty();
        ConcurrentHashMap<String, BuilderMarketplace> map = ensureLoaded(userId);
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
    public BuilderMarketplace reload(String userId, String id, MarketplaceConfigEntry entry) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entry, "entry");
        BuilderMarketplace next = build(userId, id, entry);
        ConcurrentHashMap<String, BuilderMarketplace> map = ensureLoaded(userId);
        BuilderMarketplace previous = map.put(id, next);
        closeQuietly(previous);
        return next;
    }

    /** Remove and close {@code userId}'s marketplace at {@code id}. No-op if not registered. */
    public boolean unregister(String userId, String id) {
        if (userId == null || id == null) return false;
        ConcurrentHashMap<String, BuilderMarketplace> map = byUser.get(userId);
        if (map == null) return false;
        BuilderMarketplace removed = map.remove(id);
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
    public BuilderMarketplace build(String userId, String id, MarketplaceConfigEntry entry) {
        if (entry.getType() == null || entry.getType().isBlank()) {
            throw new IllegalArgumentException("marketplace '" + id + "' has no type");
        }
        String type = entry.getType().toLowerCase(Locale.ROOT);
        Map<String, Object> props =
                entry.getProperties() != null ? entry.getProperties() : Map.of();
        return switch (type) {
            case "git" -> buildGit(userId, id, props);
            case "nacos" -> buildNacos(id, props);
            default ->
                    throw new IllegalArgumentException(
                            "unsupported marketplace type '"
                                    + entry.getType()
                                    + "' for '"
                                    + id
                                    + "'");
        };
    }

    /** Close every marketplace; used during shutdown so we don't leak git clones / clients. */
    @PreDestroy
    public void closeAll() {
        Collection<ConcurrentHashMap<String, BuilderMarketplace>> snapshot =
                new ArrayList<>(byUser.values());
        byUser.clear();
        for (ConcurrentHashMap<String, BuilderMarketplace> map : snapshot) {
            for (BuilderMarketplace mp : map.values()) {
                closeQuietly(mp);
            }
        }
    }

    private ConcurrentHashMap<String, BuilderMarketplace> ensureLoaded(String userId) {
        return byUser.computeIfAbsent(userId, this::hydrateFromStore);
    }

    private ConcurrentHashMap<String, BuilderMarketplace> hydrateFromStore(String userId) {
        ConcurrentHashMap<String, BuilderMarketplace> map = new ConcurrentHashMap<>();
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

    private BuilderMarketplace buildGit(String userId, String id, Map<String, Object> props) {
        String remoteUrl = stringProp(props, "remoteUrl");
        if (remoteUrl == null) {
            throw new IllegalArgumentException(
                    "git marketplace '" + id + "' requires a non-empty 'remoteUrl'");
        }
        String branch = stringProp(props, "branch");
        // Each user gets a separate clone directory under the platform-wide marketplaces cache so
        // user A's checkout cannot poison user B's read.
        Path localPath =
                sharedWorkspacePaths
                        .workspaceRoot()
                        .resolve(".agentscope")
                        .resolve("marketplaces")
                        .resolve("git")
                        .resolve(safeSegment(userId))
                        .resolve(safeSegment(id));
        return new GitBuilderMarketplace(id, remoteUrl, branch, localPath);
    }

    private BuilderMarketplace buildNacos(String id, Map<String, Object> props) {
        String serverAddr = stringProp(props, "serverAddr");
        if (serverAddr == null) {
            throw new IllegalArgumentException(
                    "nacos marketplace '" + id + "' requires a non-empty 'serverAddr'");
        }
        return new NacosBuilderMarketplace(
                id,
                serverAddr,
                stringProp(props, "namespaceId"),
                stringProp(props, "username"),
                stringProp(props, "password"),
                stringProp(props, "accessKey"),
                stringProp(props, "secretKey"));
    }

    private static String stringProp(Map<String, Object> props, String key) {
        Object v = props.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String safeSegment(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void closeQuietly(BuilderMarketplace mp) {
        if (mp == null) return;
        try {
            mp.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close marketplace '{}': {}", mp.id(), e.getMessage(), e);
        }
    }
}
