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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.harness.agent.IsolationScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filesystem-backed implementation of {@link SandboxStateStore}.
 *
 * <p>On-disk layout (relative to {@code workspaceRoot}):
 *
 * <pre>
 * SESSION scope  → agents/&lt;agentId&gt;/context/&lt;safe(sessionId)&gt;/_sandbox.json
 * USER scope     → agents/&lt;agentId&gt;/sandboxes/user/&lt;safe(userId)&gt;.json
 * AGENT scope    → agents/&lt;agentId&gt;/sandboxes/agent.json
 * GLOBAL scope   → sandboxes/global.json
 * </pre>
 *
 * <p>Values that contain characters outside {@code [a-zA-Z0-9_\-.]} are Base64url-encoded
 * (no padding) to produce filesystem-safe filenames.
 */
public final class WorkspaceSandboxStateStore implements SandboxStateStore {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSandboxStateStore.class);

    private static final Pattern SAFE_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-.]+$");

    private final Path workspaceRoot;
    private final String agentId;

    /**
     * Creates a store backed by the given workspace directory.
     *
     * @param workspaceRoot root of the agent workspace
     * @param agentId       agent name (used in path construction for all scopes)
     */
    public WorkspaceSandboxStateStore(Path workspaceRoot, String agentId) {
        this.workspaceRoot = workspaceRoot;
        this.agentId = agentId;
    }

    @Override
    public Optional<String> load(SandboxIsolationKey key) throws IOException {
        Path file = resolveFile(key);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Override
    public void save(SandboxIsolationKey key, String json) throws IOException {
        Path file = resolveFile(key);
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        log.debug("[sandbox-store] Saved state to {}", file);
    }

    @Override
    public void delete(SandboxIsolationKey key) throws IOException {
        Path file = resolveFile(key);
        Files.deleteIfExists(file);
        log.debug("[sandbox-store] Deleted state at {}", file);
    }

    /** Resolves the file path for the given key. */
    private Path resolveFile(SandboxIsolationKey key) {
        IsolationScope scope = key.getScope();
        if (scope == IsolationScope.SESSION) {
            return workspaceRoot
                    .resolve("agents")
                    .resolve(agentId)
                    .resolve("context")
                    .resolve(safeName(key.getValue()))
                    .resolve("_sandbox.json");
        } else if (scope == IsolationScope.USER) {
            return workspaceRoot
                    .resolve("agents")
                    .resolve(agentId)
                    .resolve("sandboxes")
                    .resolve("user")
                    .resolve(safeName(key.getValue()) + ".json");
        } else if (scope == IsolationScope.AGENT) {
            return workspaceRoot
                    .resolve("agents")
                    .resolve(agentId)
                    .resolve("sandboxes")
                    .resolve("agent.json");
        } else {
            return workspaceRoot.resolve("sandboxes").resolve("global.json");
        }
    }

    private static String safeName(String value) {
        if (SAFE_PATTERN.matcher(value).matches()) {
            return value;
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
