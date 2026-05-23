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
package io.agentscope.harness.claw.app.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent registry that maps a claw {@code userId} to that user's identity on other channels
 * (Slack, Discord, GitHub, ...). The data is stored at
 * {@code {cwd}/.agentscope/identity-links.json} as:
 *
 * <pre>{@code
 * {
 *   "user-42": {
 *     "slack":   "U7F9LZK1A",
 *     "discord": "212345678901234567"
 *   },
 *   "user-9": {
 *     "github": "alice"
 *   }
 * }
 * }</pre>
 *
 * <p>OpenClaw uses identity links to fan-out a message to the same logical user across multiple
 * channels (a {@code /dock_<channel>} command attaches the user's identity on that channel). In
 * agentscope-claw the store is the configuration source — channel adapters consult it to map
 * incoming events to a known {@code userId} and to deliver outbound messages back through the
 * matching channel.
 */
public class IdentityLinkStore {

    private static final Logger log = LoggerFactory.getLogger(IdentityLinkStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ConcurrentHashMap<String, Map<String, String>> byUser = new ConcurrentHashMap<>();

    public IdentityLinkStore(Path agentscopeDir) {
        this.file = agentscopeDir.resolve("identity-links.json");
        load();
    }

    // -----------------------------------------------------------------
    //  Query
    // -----------------------------------------------------------------

    /** Returns a snapshot of the entire user → channel → externalId map. */
    public Map<String, Map<String, String>> snapshot() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        byUser.forEach((u, m) -> out.put(u, new LinkedHashMap<>(m)));
        return out;
    }

    /** Returns the user's channel links, or an empty map when none exist. */
    public Map<String, String> linksFor(String userId) {
        return new LinkedHashMap<>(byUser.getOrDefault(userId, Map.of()));
    }

    /** Resolves the external identity for {@code userId} on {@code channelId}. */
    public Optional<String> externalIdFor(String userId, String channelId) {
        Map<String, String> m = byUser.get(userId);
        if (m == null) return Optional.empty();
        return Optional.ofNullable(m.get(channelId));
    }

    /**
     * Reverse lookup: returns the claw {@code userId} whose link for {@code channelId} matches
     * {@code externalId}. Useful for inbound delivery to translate a channel-native id into the
     * canonical user id.
     */
    public Optional<String> userIdByExternal(String channelId, String externalId) {
        if (channelId == null || externalId == null) return Optional.empty();
        for (var entry : byUser.entrySet()) {
            if (externalId.equals(entry.getValue().get(channelId))) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    // -----------------------------------------------------------------
    //  Mutations
    // -----------------------------------------------------------------

    /**
     * Records that {@code userId} is known as {@code externalId} on {@code channelId}. Replaces
     * any prior value for the same (user, channel) pair.
     */
    public void link(String userId, String channelId, String externalId) {
        if (userId == null || channelId == null || externalId == null) {
            throw new IllegalArgumentException("userId, channelId, externalId are required");
        }
        writeLock.lock();
        try {
            Map<String, String> links = byUser.computeIfAbsent(userId, k -> new LinkedHashMap<>());
            synchronized (links) {
                links.put(channelId, externalId);
            }
            persist();
            log.info(
                    "Identity link added: user={}, channel={}, externalId={}",
                    userId,
                    channelId,
                    externalId);
        } finally {
            writeLock.unlock();
        }
    }

    /** Removes the link for {@code (userId, channelId)} if present. */
    public boolean unlink(String userId, String channelId) {
        writeLock.lock();
        try {
            Map<String, String> links = byUser.get(userId);
            if (links == null) return false;
            String removed;
            synchronized (links) {
                removed = links.remove(channelId);
                if (links.isEmpty()) byUser.remove(userId);
            }
            if (removed != null) {
                persist();
                return true;
            }
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    // -----------------------------------------------------------------
    //  Persistence
    // -----------------------------------------------------------------

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Map<String, String>> data =
                    MAPPER.readValue(
                            json, new TypeReference<Map<String, Map<String, String>>>() {});
            data.forEach((u, m) -> byUser.put(u, new LinkedHashMap<>(m)));
            log.info("Loaded {} identity-link records from {}", byUser.size(), file);
        } catch (IOException e) {
            log.warn("Failed to load identity-links from {}: {}", file, e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            ObjectMapper m =
                    MAPPER.copy()
                            .enable(
                                    com.fasterxml.jackson.databind.SerializationFeature
                                            .INDENT_OUTPUT);
            m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, m.writeValueAsString(snapshot()), StandardCharsets.UTF_8);
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to persist identity-links: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
