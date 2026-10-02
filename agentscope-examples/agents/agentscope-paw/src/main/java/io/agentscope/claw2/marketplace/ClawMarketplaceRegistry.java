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
package io.agentscope.claw2.marketplace;

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.MarketplaceConfigEntry;
import jakarta.annotation.PostConstruct;
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
 * Live registry of claw-managed {@link ClawMarketplace} instances, keyed by user-chosen id.
 *
 * <p>The registry is the only place that knows how to map a {@link MarketplaceConfigEntry} to a
 * concrete implementation, so adding a new marketplace type means touching the {@link
 * #build(String, MarketplaceConfigEntry)} switch and nothing else.
 *
 * <p>Lifecycle: {@link MarketplacePersistence} drives mutations through {@link
 * #reload(String, MarketplaceConfigEntry)} and {@link #unregister(String)} after it has written
 * the new config to disk. The registry takes ownership of the {@link ClawMarketplace#close()}
 * call when an instance is being replaced or removed so the previous git clone / nacos client
 * is released eagerly rather than waiting on GC.
 */
@Component
public class ClawMarketplaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClawMarketplaceRegistry.class);

    private final ClawBootstrap bootstrap;
    private final ConcurrentHashMap<String, ClawMarketplace> instances = new ConcurrentHashMap<>();

    public ClawMarketplaceRegistry(ClawBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    /**
     * Build every marketplace declared in the bootstrap-time config. Called once on startup
     * after the Spring context is wired. Failures for individual marketplaces are logged but
     * do not abort startup — claw should still come up if one upstream is misconfigured.
     */
    @PostConstruct
    public void initFromBootstrapConfig() {
        Map<String, MarketplaceConfigEntry> declared =
                bootstrap.loadedConfig() != null
                        ? bootstrap.loadedConfig().getMarketplaces()
                        : null;
        if (declared == null || declared.isEmpty()) {
            return;
        }
        for (Map.Entry<String, MarketplaceConfigEntry> e : declared.entrySet()) {
            try {
                reload(e.getKey(), e.getValue());
            } catch (RuntimeException ex) {
                log.warn(
                        "Failed to initialise marketplace '{}' from agentscope.json: {}",
                        e.getKey(),
                        ex.getMessage(),
                        ex);
            }
        }
    }

    /** Snapshot of every live marketplace, ordered by id for stable UI rendering. */
    public List<ClawMarketplace> list() {
        List<ClawMarketplace> snapshot = new ArrayList<>(instances.values());
        snapshot.sort(Comparator.comparing(ClawMarketplace::id));
        return snapshot;
    }

    /** Returns the marketplace with the given id, if registered. */
    public Optional<ClawMarketplace> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(instances.get(id));
    }

    /** Whether a marketplace with the given id is currently registered. */
    public boolean contains(String id) {
        return id != null && instances.containsKey(id);
    }

    /**
     * Replace (or first-time install) the marketplace at {@code id} with one built from
     * {@code entry}. The previously registered instance, if any, is {@link
     * ClawMarketplace#close() closed} after the new one is in place so callers continue to see a
     * live entry even mid-reload.
     */
    public ClawMarketplace reload(String id, MarketplaceConfigEntry entry) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entry, "entry");
        ClawMarketplace next = build(id, entry);
        ClawMarketplace previous = instances.put(id, next);
        closeQuietly(previous);
        return next;
    }

    /** Remove and close the marketplace at {@code id}. No-op if it was not registered. */
    public boolean unregister(String id) {
        if (id == null) return false;
        ClawMarketplace removed = instances.remove(id);
        if (removed == null) return false;
        closeQuietly(removed);
        return true;
    }

    /** Close every marketplace; used during shutdown so we don't leak git clones / clients. */
    @PreDestroy
    public void closeAll() {
        Collection<ClawMarketplace> snapshot = new ArrayList<>(instances.values());
        instances.clear();
        snapshot.forEach(this::closeQuietly);
    }

    /**
     * Build a marketplace instance from a config entry without registering it. Used by
     * {@code MarketplacesController#test} so a connection probe runs against the same code path
     * a real registration would use, but without taking the (id) slot if the probe fails.
     *
     * @throws IllegalArgumentException if {@code entry.type} is unknown or required fields are missing
     */
    public ClawMarketplace build(String id, MarketplaceConfigEntry entry) {
        if (entry.getType() == null || entry.getType().isBlank()) {
            throw new IllegalArgumentException("marketplace '" + id + "' has no type");
        }
        String type = entry.getType().toLowerCase(Locale.ROOT);
        Map<String, Object> props =
                entry.getProperties() != null ? entry.getProperties() : Map.of();
        return switch (type) {
            case "git" -> buildGit(id, props);
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

    private ClawMarketplace buildGit(String id, Map<String, Object> props) {
        String remoteUrl = stringProp(props, "remoteUrl");
        if (remoteUrl == null) {
            throw new IllegalArgumentException(
                    "git marketplace '" + id + "' requires a non-empty 'remoteUrl'");
        }
        String branch = stringProp(props, "branch");
        String skillsRoot = stringProp(props, "skillsRoot");
        Path localPath = bootstrap.clawHome().resolve("marketplaces").resolve("git").resolve(id);
        return new GitClawMarketplace(id, remoteUrl, branch, localPath, skillsRoot);
    }

    private ClawMarketplace buildNacos(String id, Map<String, Object> props) {
        String serverAddr = stringProp(props, "serverAddr");
        if (serverAddr == null) {
            throw new IllegalArgumentException(
                    "nacos marketplace '" + id + "' requires a non-empty 'serverAddr'");
        }
        return new NacosClawMarketplace(
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

    private void closeQuietly(ClawMarketplace mp) {
        if (mp == null) return;
        try {
            mp.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close marketplace '{}': {}", mp.id(), e.getMessage(), e);
        }
    }
}
