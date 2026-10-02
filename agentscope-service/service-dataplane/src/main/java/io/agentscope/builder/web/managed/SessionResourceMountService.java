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
package io.agentscope.builder.web.managed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Mounts session-level {@code resources[]} entries (see {@code
 * ManagedSessionService.CreateSessionRequest#resources}) into a managed session's workspace at
 * agent-build time.
 *
 * <p>Supported resource {@code type} values:
 *
 * <ul>
 *   <li>{@code github_repository} — shallow-clones the repository into {@code
 *       resources/{slug}/} via a host {@code git} process. Requires {@code git} on {@code PATH};
 *       failures are logged as warnings and do not fail agent construction.
 *   <li>{@code file} — reserved for {@code file_id}-backed mounts (uploaded workspace files).
 *       Not yet implemented; writes a placeholder note so operators can see the mount was
 *       requested.
 * </ul>
 */
@Service
public class SessionResourceMountService {

    private static final Logger log = LoggerFactory.getLogger(SessionResourceMountService.class);

    /** Directory (relative to the agent workspace) under which resources are mounted. */
    public static final String RESOURCES_ROOT = "resources";

    private static final Duration CLONE_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Applies each resource descriptor onto the workspace ahead of {@code builder.build()}. This
     * is a best-effort, synchronous step: a failing mount is logged and skipped rather than
     * aborting session creation, since the agent can still run without the extra resource.
     */
    public void apply(Path workspace, List<Map<String, Object>> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        Path resourcesRoot = workspace.resolve(RESOURCES_ROOT);
        for (Map<String, Object> resource : resources) {
            if (resource == null) {
                continue;
            }
            String type = stringValue(resource.get("type"));
            if (type == null) {
                log.warn("Skipping session resource with missing 'type': {}", resource);
                continue;
            }
            try {
                switch (type) {
                    case "github_repository" -> mountGithubRepository(resourcesRoot, resource);
                    case "file" -> mountFilePlaceholder(resourcesRoot, resource);
                    default -> log.warn("Unknown session resource type '{}'; skipping", type);
                }
            } catch (Exception ex) {
                log.warn("Failed to mount session resource (type={}): {}", type, ex.getMessage());
            }
        }
    }

    private void mountGithubRepository(Path resourcesRoot, Map<String, Object> resource)
            throws IOException {
        String repoUrl = firstNonBlank(resource.get("url"), resource.get("repoUrl"));
        if (repoUrl == null) {
            log.warn("github_repository resource missing 'url': {}", resource);
            return;
        }
        String slug = resolveSlug(resource, repoUrl);
        Path target = resourcesRoot.resolve(slug);
        if (Files.exists(target) && Files.isDirectory(target) && directoryNonEmpty(target)) {
            log.debug("Resource '{}' already cloned at {}; skipping", slug, target);
            return;
        }
        Files.createDirectories(resourcesRoot);
        if (!gitAvailable()) {
            log.warn(
                    "git binary not found on PATH; cannot clone github_repository resource '{}'"
                            + " into {}",
                    slug,
                    target);
            return;
        }
        String ref = stringValue(resource.get("ref"));
        if (ref == null) {
            ref = stringValue(resource.get("branch"));
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("clone");
        command.add("--depth");
        command.add("1");
        if (ref != null && !ref.isBlank()) {
            command.add("--branch");
            command.add(ref);
        }
        command.add(repoUrl);
        command.add(target.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(CLONE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn(
                        "Timed out cloning github_repository resource '{}' from {}", slug, repoUrl);
                return;
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                log.warn("git clone failed for resource '{}' ({}): {}", slug, repoUrl, output);
                return;
            }
            log.info(
                    "Mounted github_repository resource '{}' from {} into {}",
                    slug,
                    repoUrl,
                    target);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while cloning github_repository resource '{}'", slug);
        }
    }

    private void mountFilePlaceholder(Path resourcesRoot, Map<String, Object> resource)
            throws IOException {
        String fileId = stringValue(resource.get("fileId"));
        String slug = resolveSlug(resource, fileId != null ? fileId : "file");
        Files.createDirectories(resourcesRoot);
        String inlineContent = stringValue(resource.get("content"));
        if (inlineContent != null && !inlineContent.isBlank()) {
            String filename = stringValue(resource.get("filename"));
            if (filename == null || filename.isBlank()) {
                filename = slug + ".txt";
            }
            Path target = resourcesRoot.resolve(sanitize(filename));
            Files.writeString(target, inlineContent, StandardCharsets.UTF_8);
            log.info("Staged inline file resource '{}' at {}", slug, target);
            return;
        }
        Path note = resourcesRoot.resolve(slug + ".NOTE.md");
        if (Files.exists(note)) {
            return;
        }
        String content =
                "# Reserved file mount\n\n"
                        + "This session requested a `file` resource mount (fileId="
                        + (fileId != null ? fileId : "unknown")
                        + "), but no inline content was provided and file_id-backed mounts are not"
                        + " yet fully implemented.\n"
                        + "The resource is recorded here so operators can see the request.\n";
        Files.writeString(note, content, StandardCharsets.UTF_8);
        log.info("Recorded reserved file-resource placeholder at {}", note);
    }

    /**
     * Mirrors {@code type=file} resource payloads into the control-plane {@link
     * io.agentscope.builder.web.catalog.DefinitionStore} so replicas can restage Hands workspace
     * content without depending on node-local clones. GitHub clones remain Hands-local staging
     * (require {@code git} on the turn-owner node).
     */
    public void mirrorFileResourcesToDefinitionStore(
            io.agentscope.builder.web.catalog.DefinitionStore store,
            String ownerId,
            String agentId,
            List<Map<String, Object>> resources) {
        if (store == null || resources == null || resources.isEmpty()) {
            return;
        }
        if (ownerId == null || agentId == null) {
            return;
        }
        for (Map<String, Object> resource : resources) {
            if (resource == null) {
                continue;
            }
            if (!"file".equals(stringValue(resource.get("type")))) {
                continue;
            }
            String content = stringValue(resource.get("content"));
            if (content == null || content.isBlank()) {
                continue;
            }
            String fileId = stringValue(resource.get("fileId"));
            String slug = resolveSlug(resource, fileId != null ? fileId : "file");
            String filename = stringValue(resource.get("filename"));
            if (filename == null || filename.isBlank()) {
                filename = slug + ".txt";
            }
            String path = RESOURCES_ROOT + "/" + sanitize(filename);
            try {
                store.putText(ownerId, agentId, path, content);
            } catch (Exception ex) {
                log.warn(
                        "Failed to mirror file resource '{}' into definition store: {}",
                        slug,
                        ex.getMessage());
            }
        }
    }

    /**
     * Restages definition-store {@code resources/**} files onto the Hands workspace Path so a
     * replica without prior local clones can still see inline file resources.
     */
    public void restageFromDefinitionStore(
            io.agentscope.builder.web.catalog.DefinitionStore store,
            String ownerId,
            String agentId,
            Path workspace) {
        if (store == null || workspace == null || ownerId == null || agentId == null) {
            return;
        }
        try {
            List<String> keys = store.list(ownerId, agentId, RESOURCES_ROOT);
            if (keys.isEmpty()) {
                return;
            }
            Path root = workspace.resolve(RESOURCES_ROOT);
            Files.createDirectories(root);
            for (String key : keys) {
                String relative =
                        key.startsWith(RESOURCES_ROOT + "/")
                                ? key.substring(RESOURCES_ROOT.length() + 1)
                                : key;
                if (relative.isBlank() || relative.contains("..")) {
                    continue;
                }
                store.getText(ownerId, agentId, key)
                        .ifPresent(
                                body -> {
                                    try {
                                        Path target = root.resolve(relative).normalize();
                                        if (!target.startsWith(root)) {
                                            return;
                                        }
                                        if (target.getParent() != null) {
                                            Files.createDirectories(target.getParent());
                                        }
                                        Files.writeString(target, body, StandardCharsets.UTF_8);
                                    } catch (IOException ex) {
                                        log.warn(
                                                "Failed to restage definition resource {}: {}",
                                                key,
                                                ex.getMessage());
                                    }
                                });
            }
        } catch (Exception ex) {
            log.warn(
                    "Failed to restage definition resources for {}/{}: {}",
                    ownerId,
                    agentId,
                    ex.getMessage());
        }
    }

    private static boolean directoryNonEmpty(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        }
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String resolveSlug(Map<String, Object> resource, String fallbackSource) {
        String slug = stringValue(resource.get("slug"));
        if (slug != null && !slug.isBlank()) {
            return sanitize(slug);
        }
        return sanitize(deriveSlugFrom(fallbackSource));
    }

    private static String deriveSlugFrom(String source) {
        if (source == null || source.isBlank()) {
            return "resource";
        }
        String trimmed = source.trim();
        while (trimmed.endsWith("/") || trimmed.endsWith(".git")) {
            trimmed =
                    trimmed.endsWith(".git")
                            ? trimmed.substring(0, trimmed.length() - 4)
                            : trimmed.substring(0, trimmed.length() - 1);
        }
        int lastSlash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
        return lastSlash >= 0 && lastSlash < trimmed.length() - 1
                ? trimmed.substring(lastSlash + 1)
                : trimmed;
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "resource";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String s = stringValue(value);
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
