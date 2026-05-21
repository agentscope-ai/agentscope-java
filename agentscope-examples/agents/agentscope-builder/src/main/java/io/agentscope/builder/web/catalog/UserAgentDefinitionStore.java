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
package io.agentscope.builder.web.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.config.AgentConfigEntry;
import io.agentscope.builder.web.share.AgentShareGrant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JSON-file-backed store for per-user custom agent definitions.
 *
 * <p>Each user's definitions are persisted at
 * {@code {cwd}/.agentscope/users/{userId}/agents.json}. Definitions are cached in-memory and
 * lazily loaded on first access per user.
 *
 * <p>This class is thread-safe: all mutations are synchronized on the per-user lock.
 */
@Component
public class UserAgentDefinitionStore {

    private static final Logger log = LoggerFactory.getLogger(UserAgentDefinitionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** In-memory cache: userId → (agentId → StoredEntry). Lazily populated. */
    private final ConcurrentHashMap<String, Map<String, StoredEntry>> cache =
            new ConcurrentHashMap<>();

    /** Locks per userId to prevent concurrent file writes. */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private final Path baseDir;

    public UserAgentDefinitionStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    // -----------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------

    /** Returns all agent definitions owned by {@code userId}. */
    public List<StoredEntry> list(String userId) {
        return new ArrayList<>(loadForUser(userId).values());
    }

    /** Finds a single agent definition by id for the given user. */
    public Optional<StoredEntry> findById(String userId, String agentId) {
        return Optional.ofNullable(loadForUser(userId).get(agentId));
    }

    /**
     * Saves (creates or updates) an agent definition for the given user. Persists atomically to
     * the user's JSON file.
     */
    public StoredEntry save(String userId, StoredEntry entry) {
        synchronized (lockFor(userId)) {
            Map<String, StoredEntry> userMap = loadForUser(userId);
            userMap.put(entry.id(), entry);
            persist(userId, userMap);
            return entry;
        }
    }

    /**
     * Deletes an agent definition. Returns {@code true} if the entry existed and was removed.
     */
    public boolean delete(String userId, String agentId) {
        synchronized (lockFor(userId)) {
            Map<String, StoredEntry> userMap = loadForUser(userId);
            if (userMap.remove(agentId) == null) {
                return false;
            }
            persist(userId, userMap);
            return true;
        }
    }

    // -----------------------------------------------------------------
    //  Stored data model
    // -----------------------------------------------------------------

    /**
     * JSON-serializable agent definition stored per user. All fields are optional except {@code
     * id}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredEntry(
            String id,
            String name,
            String description,
            String sysPrompt,
            String model,
            Integer maxIters,
            List<String> toolsAllow,
            List<String> toolsDeny,
            String identityName,
            String identityEmoji,
            List<String> groupChatMentionPatterns,
            Boolean groupChatRequireMention,
            String sandboxMode,
            String sandboxScope,
            List<String> skillsAllow,
            List<String> skillsDeny,
            long createdAt,
            long updatedAt,
            List<AgentShareGrant> shares,
            String runAs,
            String forkOf) {

        public AgentDefinition toDefinition(String ownerId) {
            return new AgentDefinition(
                    id,
                    name != null ? name : id,
                    description,
                    sysPrompt,
                    model,
                    maxIters,
                    null, // effective tool list resolved at runtime
                    toolsAllow,
                    toolsDeny,
                    identityName,
                    identityEmoji,
                    groupChatMentionPatterns,
                    groupChatRequireMention,
                    sandboxMode,
                    sandboxScope,
                    skillsAllow,
                    skillsDeny,
                    AgentDefinition.SCOPE_USER,
                    ownerId,
                    createdAt,
                    updatedAt,
                    shares,
                    runAs != null ? runAs : AgentDefinition.RUN_AS_INVOKER,
                    forkOf,
                    null); // tierForCurrentUser — populated by the controller
        }

        /** Convert to a partial {@link AgentConfigEntry} for runtime agent construction. */
        public AgentConfigEntry toConfigEntry() {
            AgentConfigEntry e = new AgentConfigEntry();
            e.setName(name);
            e.setDescription(description);
            e.setSysPrompt(sysPrompt);
            e.setModel(model);
            e.setMaxIters(maxIters);
            if (toolsAllow != null || toolsDeny != null) {
                AgentConfigEntry.ToolsConfig tc = new AgentConfigEntry.ToolsConfig();
                tc.setAllow(toolsAllow);
                tc.setDeny(toolsDeny);
                e.setTools(tc);
            }
            if (identityName != null || identityEmoji != null) {
                AgentConfigEntry.IdentityConfig ic = new AgentConfigEntry.IdentityConfig();
                ic.setName(identityName);
                ic.setEmoji(identityEmoji);
                e.setIdentity(ic);
            }
            if (groupChatMentionPatterns != null || groupChatRequireMention != null) {
                AgentConfigEntry.GroupChatConfig gc = new AgentConfigEntry.GroupChatConfig();
                gc.setMentionPatterns(groupChatMentionPatterns);
                gc.setRequireMention(groupChatRequireMention);
                e.setGroupChat(gc);
            }
            if (sandboxMode != null || sandboxScope != null) {
                AgentConfigEntry.SandboxConfig sc = new AgentConfigEntry.SandboxConfig();
                sc.setMode(sandboxMode);
                sc.setScope(sandboxScope);
                e.setSandbox(sc);
            }
            if (skillsAllow != null || skillsDeny != null) {
                AgentConfigEntry.SkillsConfig sk = new AgentConfigEntry.SkillsConfig();
                sk.setAllow(skillsAllow);
                sk.setDeny(skillsDeny);
                e.setSkills(sk);
            }
            return e;
        }
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private Map<String, StoredEntry> loadForUser(String userId) {
        return cache.computeIfAbsent(userId, this::loadFromDisk);
    }

    private Map<String, StoredEntry> loadFromDisk(String userId) {
        Path file = agentsFile(userId);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            List<StoredEntry> entries =
                    MAPPER.readValue(file.toFile(), new TypeReference<List<StoredEntry>>() {});
            Map<String, StoredEntry> map = new LinkedHashMap<>();
            for (StoredEntry e : entries) {
                map.put(e.id(), e);
            }
            return map;
        } catch (IOException e) {
            log.warn("Failed to load user agent definitions from {}: {}", file, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void persist(String userId, Map<String, StoredEntry> entries) {
        Path file = agentsFile(userId);
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(file.toFile(), new ArrayList<>(entries.values()));
        } catch (IOException e) {
            log.error("Failed to persist user agent definitions to {}: {}", file, e.getMessage());
            throw new RuntimeException("Failed to save agent definitions", e);
        }
    }

    private Path agentsFile(String userId) {
        return baseDir.resolve("users").resolve(userId).resolve("agents.json");
    }

    private Object lockFor(String userId) {
        return locks.computeIfAbsent(userId, k -> new Object());
    }
}
