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
package io.agentscope.claw2.web.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.config.AgentConfigEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * User-defined agent catalog backed by a single JSON file at {@code ${clawHome}/agents.json}.
 *
 * <p>Stores the lightweight metadata for every custom agent (id, name, description, system prompt,
 * model overrides, tool allow/deny lists, ...). Workspace files (AGENTS.md, skills, subagents,
 * memory, ...) live separately under each agent's workspace directory and are managed by
 * {@link io.agentscope.claw2.web.api.AgentWorkspaceController}.
 *
 * <p>Atomic write via temp-file + rename so a crash during persistence never leaves a partial
 * file. Thread-safe through a {@link ReentrantReadWriteLock}.
 */
@Component
public final class UserAgentDefinitionStore {

    private static final Logger log = LoggerFactory.getLogger(UserAgentDefinitionStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storeFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();

    @Autowired
    public UserAgentDefinitionStore(ClawBootstrap bootstrap) {
        this(bootstrap.clawHome().resolve("agents.json"));
    }

    public UserAgentDefinitionStore(Path storeFile) {
        this.storeFile = storeFile;
        load();
    }

    /** Loads the entries map from disk; missing/blank file starts empty. */
    private void load() {
        lock.writeLock().lock();
        try {
            entries.clear();
            if (!Files.isRegularFile(storeFile)) {
                return;
            }
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            Map<String, StoredEntry> loaded =
                    MAPPER.readValue(
                            json, new TypeReference<LinkedHashMap<String, StoredEntry>>() {});
            if (loaded != null) {
                entries.putAll(loaded);
            }
            log.info("Loaded {} custom agent definitions from {}", entries.size(), storeFile);
        } catch (IOException e) {
            log.warn(
                    "Failed to load custom agent definitions from {}: {}",
                    storeFile,
                    e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** All custom agent definitions in insertion order. */
    public List<StoredEntry> list() {
        lock.readLock().lock();
        try {
            return List.copyOf(entries.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<StoredEntry> findById(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            return Optional.ofNullable(entries.get(agentId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Inserts or updates {@code entry}. The returned value is the persisted entry. */
    public StoredEntry save(StoredEntry entry) {
        if (entry == null || entry.id() == null || entry.id().isBlank()) {
            throw new IllegalArgumentException("entry.id is required");
        }
        lock.writeLock().lock();
        try {
            entries.put(entry.id(), entry);
            flush();
            return entry;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Deletes the entry identified by {@code agentId}. Returns {@code true} if it existed. */
    public boolean delete(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        lock.writeLock().lock();
        try {
            if (entries.remove(agentId) != null) {
                flush();
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** The backing JSON file (typically {@code ${clawHome}/agents.json}). */
    public Path storeFile() {
        return storeFile;
    }

    private void flush() {
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            byte[] bytes = MAPPER.writeValueAsBytes(entries);
            Files.write(tmp, bytes);
            try {
                Files.move(
                        tmp,
                        storeFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn(
                    "Failed to persist custom agent definitions to {}: {}",
                    storeFile,
                    e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Stored data model — stable JSON wire format
    // -----------------------------------------------------------------

    /** JSON-serializable agent definition. All fields except {@code id} are optional. */
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
            String workspacePath) {

        public AgentDefinition toDefinition() {
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
                    createdAt,
                    updatedAt,
                    workspacePath,
                    false);
        }

        /** Builds a partial {@link AgentConfigEntry} so the runtime can instantiate this agent. */
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
}
