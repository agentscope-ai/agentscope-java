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
package io.agentscope.harness.claw.app.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Resolves and materialises the per-user workspace tree that each tenant's DataAgent reads and
 * writes against.
 *
 * <p>The directory layout this provisioner manages:
 *
 * <pre>
 * ${agentscopeDir}/
 *   templates/                          ← bundled classpath workspace template, refreshed on boot
 *   users/{userId}/workspace/           ← per-user runtime workspace (this provisioner's main job)
 *     AGENTS.md
 *     skills/...
 *     subagents/...
 *     memory/                           ← agent writes here at runtime
 *     knowledge/                        ← user uploads here through the UI
 *     sessions/                         ← per-session transcripts and workspace mutation logs
 * </pre>
 *
 * <p>{@link #resolveUserWorkspace(String)} is idempotent: on the first call for a given user the
 * bundled template is copied into the user's workspace; on subsequent calls only the path is
 * returned. Existing files inside a user's workspace are never overwritten — users edit their own
 * skills/AGENTS.md freely.
 *
 * <p>This class is thread-safe: per-user materialisation is serialised through a
 * {@link ConcurrentHashMap}, so two concurrent first-time chats for the same user won't race.
 */
public final class UserWorkspaceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(UserWorkspaceProvisioner.class);

    /**
     * Classpath subtree copied into each user's workspace. The bundled template lives at
     * {@code classpath:/workspace-template/workspace/**}; everything outside that subtree (e.g.
     * {@code agentscope.json}) is global config, not per-user runtime state.
     */
    private static final String WORKSPACE_TEMPLATE_CLASSPATH_ROOT = "workspace-template/workspace";

    private static final String CLASSPATH_PATTERN =
            "classpath*:/" + WORKSPACE_TEMPLATE_CLASSPATH_ROOT + "/**/*";

    /**
     * Restricts user identifiers to characters that are safe to embed in filesystem paths on every
     * mainstream OS. Rejects path separators, traversal sequences, and shell metacharacters.
     */
    private static final Pattern USER_ID_PATTERN = Pattern.compile("[a-zA-Z0-9._@-]+");

    private final Path agentscopeDir;
    private final ConcurrentHashMap<String, Path> materialised = new ConcurrentHashMap<>();

    public UserWorkspaceProvisioner(Path agentscopeDir) {
        this.agentscopeDir = Objects.requireNonNull(agentscopeDir, "agentscopeDir").normalize();
    }

    /** Root of the {@code .agentscope/} directory this provisioner manages. */
    public Path agentscopeDir() {
        return agentscopeDir;
    }

    /** Path to {@code .agentscope/users/}. Does not create the directory. */
    public Path usersRoot() {
        return agentscopeDir.resolve("users");
    }

    /** Path to {@code .agentscope/templates/}. Does not create the directory. */
    public Path templatesDir() {
        return agentscopeDir.resolve("templates");
    }

    /**
     * Resolves the runtime workspace for {@code userId}, materialising the bundled template into
     * the directory on first call.
     *
     * <p>The returned path is {@code .agentscope/users/{userId}/workspace}. The directory will
     * exist and contain at least the template skeleton after this call returns.
     *
     * @throws IllegalArgumentException if {@code userId} is null, blank, or contains characters
     *     unsafe to embed in a filesystem path.
     */
    public Path resolveUserWorkspace(String userId) {
        validateUserId(userId);
        return materialised.computeIfAbsent(userId, this::materialiseForUser);
    }

    /**
     * Refreshes the cached path for {@code userId} so the next {@link #resolveUserWorkspace} call
     * re-materialises the template if the directory has been wiped externally. Mainly useful in
     * tests; production code does not need to call this.
     */
    public void invalidate(String userId) {
        if (userId != null) {
            materialised.remove(userId);
        }
    }

    /**
     * Resolves a user-owned path inside the user's workspace, guarding against path-traversal.
     * The returned path is guaranteed to be inside {@code resolveUserWorkspace(userId)}.
     *
     * @throws IllegalArgumentException if the resolved path escapes the user's workspace.
     */
    public Path resolveInsideUserWorkspace(String userId, String relative) {
        Path root = resolveUserWorkspace(userId);
        if (relative == null || relative.isBlank()) return root;
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Path escapes user workspace: userId=" + userId + ", relative=" + relative);
        }
        return resolved;
    }

    /**
     * Validates that {@code userId} is safe to embed in a filesystem path. Public so call sites
     * (e.g. controllers) can fail fast on bad input before doing any work.
     */
    public static void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (userId.length() > 200) {
            throw new IllegalArgumentException("userId too long: " + userId.length());
        }
        if (!USER_ID_PATTERN.matcher(userId).matches()) {
            throw new IllegalArgumentException(
                    "userId contains characters that are unsafe for filesystem paths: " + userId);
        }
        if (userId.startsWith(".") || userId.contains("..")) {
            throw new IllegalArgumentException("userId may not contain path traversal: " + userId);
        }
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private Path materialiseForUser(String userId) {
        Path userWs = usersRoot().resolve(userId).resolve("workspace").normalize();
        try {
            Files.createDirectories(userWs);
            int written = copyClasspathTemplateInto(userWs);
            if (written > 0) {
                log.info(
                        "Materialised workspace template into user dir: userId={}, files={},"
                                + " path={}",
                        userId,
                        written,
                        userWs);
            }
            ensureRuntimeSubdirs(userWs);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to materialise workspace for user " + userId + " at " + userWs, e);
        }
        return userWs;
    }

    /**
     * Copies {@code classpath:/workspace-template/workspace/**} into {@code targetDir}, preserving
     * any existing files. Returns the number of new files written.
     */
    private static int copyClasspathTemplateInto(Path targetDir) throws IOException {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(
                        UserWorkspaceProvisioner.class.getClassLoader());
        Resource[] resources;
        try {
            resources = resolver.getResources(CLASSPATH_PATTERN);
        } catch (IOException ioe) {
            log.warn(
                    "No workspace template resources found on classpath ({}): {}",
                    CLASSPATH_PATTERN,
                    ioe.getMessage());
            return 0;
        }
        if (resources.length == 0) {
            log.warn("No classpath resources matched {} — skipping copy", CLASSPATH_PATTERN);
            return 0;
        }

        String marker = "/" + WORKSPACE_TEMPLATE_CLASSPATH_ROOT + "/";
        int written = 0;
        for (Resource r : resources) {
            if (!r.isReadable()) continue;
            String url = r.getURL().toString();
            int idx = url.indexOf(marker);
            if (idx < 0) continue;
            String rel = url.substring(idx + marker.length());
            if (rel.isEmpty() || rel.endsWith("/")) continue;

            Path dest = targetDir.resolve(rel);
            if (Files.exists(dest)) continue;

            Files.createDirectories(dest.getParent());
            try (InputStream in = r.getInputStream()) {
                Files.copy(in, dest);
                written++;
            }
        }
        return written;
    }

    /**
     * Creates empty runtime sub-directories ({@code memory/}, {@code knowledge/}, {@code sessions/})
     * so the agent's first {@code list_files} call doesn't surprise the user with missing dirs.
     */
    private static void ensureRuntimeSubdirs(Path userWs) throws IOException {
        Files.createDirectories(userWs.resolve("memory"));
        Files.createDirectories(userWs.resolve("knowledge"));
        Files.createDirectories(userWs.resolve("sessions"));
    }
}
