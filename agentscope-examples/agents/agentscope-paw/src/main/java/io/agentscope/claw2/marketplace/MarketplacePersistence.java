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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.AgentscopeConfig;
import io.agentscope.claw2.runtime.config.MarketplaceConfigEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Helper that loads, mutates, and atomically rewrites the {@code marketplaces} section of
 * {@code agentscope.json}, then drives {@link ClawMarketplaceRegistry} so changes take effect
 * in-process without a restart.
 *
 * <p>Mirrors {@code BindingPersistence}: all mutations are serialised through a {@link
 * ReentrantLock} to avoid lost-update races, and the write is atomic via {@code tmp + ATOMIC_MOVE}
 * so a crash mid-write leaves either the old or the new config — never a half-file.
 */
@Component
public class MarketplacePersistence {

    private static final Logger log = LoggerFactory.getLogger(MarketplacePersistence.class);

    private final ClawBootstrap bootstrap;
    private final ClawMarketplaceRegistry registry;
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper mapper;

    public MarketplacePersistence(ClawBootstrap bootstrap, ClawMarketplaceRegistry registry) {
        this.bootstrap = bootstrap;
        this.registry = registry;
        this.mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Apply {@code mutator} to the live marketplaces map of {@code agentscope.json}, persist the
     * result atomically, and re-register every id in {@code idsToReload} against the live
     * {@link ClawMarketplaceRegistry}. An id present in {@code idsToReload} but missing from the
     * post-mutation map is unregistered (and its previous instance closed) instead.
     */
    public <T> T mutate(MutationFn<T> mutator, List<String> idsToReload) {
        lock.lock();
        try {
            AgentscopeConfig cfg = loadConfig();
            Map<String, MarketplaceConfigEntry> marketplaces =
                    cfg.getMarketplaces() != null ? cfg.getMarketplaces() : new LinkedHashMap<>();
            cfg.setMarketplaces(marketplaces);

            T result = mutator.apply(marketplaces);
            writeAtomic(cfg);

            if (idsToReload != null) {
                for (String id : idsToReload) {
                    MarketplaceConfigEntry entry = marketplaces.get(id);
                    if (entry == null) {
                        registry.unregister(id);
                    } else {
                        try {
                            registry.reload(id, entry);
                        } catch (RuntimeException e) {
                            log.warn(
                                    "Failed to (re)register marketplace '{}' after edit: {}",
                                    id,
                                    e.getMessage(),
                                    e);
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "Invalid marketplace config for '"
                                            + id
                                            + "': "
                                            + e.getMessage(),
                                    e);
                        }
                    }
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads the current on-disk config without taking the write lock. Used by read-only
     * endpoints that want the freshest marketplaces snapshot after recent mutations.
     */
    public AgentscopeConfig currentConfig() {
        return loadConfig();
    }

    /** Functional interface for {@link #mutate(MutationFn, List)} mutations. */
    @FunctionalInterface
    public interface MutationFn<T> {
        T apply(Map<String, MarketplaceConfigEntry> marketplaces);
    }

    private AgentscopeConfig loadConfig() {
        try {
            return ClawBootstrap.loadConfigFile(bootstrap.configPath());
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read agentscope.json: " + e.getMessage());
        }
    }

    private void writeAtomic(AgentscopeConfig cfg) {
        Path target = bootstrap.configPath();
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), cfg);
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to write agentscope.json: " + e.getMessage());
        }
    }
}
