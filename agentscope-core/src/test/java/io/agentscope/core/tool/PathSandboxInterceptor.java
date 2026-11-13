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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

/**
 * Test fixture demonstrating a per-user path sandbox guard (design doc §6.1): resolves {@code
 * file_path} against the caller's workspace root, rejects escapes, and rewrites surviving paths to
 * their absolute in-sandbox form.
 *
 * <p>Hardened per security review: a strict user-id format check (an attacker-controlled id must
 * not be usable as a path component), and a real-path re-check on the nearest existing ancestor so
 * a new-file write through a symlinked directory cannot escape.
 *
 * @author shanhongyu
 */
final class PathSandboxInterceptor implements ToolCallInterceptor {

    /** Tenant ids are opaque identifiers, not path fragments. */
    private static final Pattern SAFE_USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final Function<String, Path> workspaceRootResolver;

    PathSandboxInterceptor(Function<String, Path> workspaceRootResolver) {
        this.workspaceRootResolver = workspaceRootResolver;
    }

    @Override
    public Mono<ToolResultBlock> intercept(
            ToolInvocation invocation, Function<ToolInvocation, Mono<ToolResultBlock>> next) {
        return switch (invocation.toolName()) {
            case "write_text_file" -> guardPath(invocation, next);
            default -> next.apply(invocation);
        };
    }

    /**
     * Validates the {@code file_path} argument of the guarded tool against the caller's sandbox
     * root and rewrites surviving paths to their absolute in-sandbox form.
     *
     * @param invocation the call snapshot
     * @param next       the downstream chain
     * @return the denial result, or the downstream result with the rewritten input
     */
    private Mono<ToolResultBlock> guardPath(
            ToolInvocation invocation, Function<ToolInvocation, Mono<ToolResultBlock>> next) {
        Object raw = invocation.input().get("file_path");
        if (raw == null) {
            return next.apply(invocation);
        }
        // Fail-closed on missing identity: no fallback to a shared root
        String userId =
                invocation.runtimeContext() != null
                        ? invocation.runtimeContext().getUserId()
                        : null;
        if (userId == null || userId.isBlank()) {
            return reject("no authenticated user context; cannot scope file access");
        }
        if (!SAFE_USER_ID.matcher(userId).matches()) {
            return reject("user context is malformed; cannot scope file access");
        }
        Path root = workspaceRootResolver.apply(userId).toAbsolutePath().normalize();
        // An absolute raw path is returned as-is by resolve, then rejected by startsWith
        Path resolved = root.resolve(raw.toString()).normalize();
        if (!resolved.startsWith(root) || !staysInsideRealBoundary(resolved, root)) {
            return reject("path must stay inside your workspace; use relative paths");
        }
        Map<String, Object> rewritten = new HashMap<>(invocation.input());
        rewritten.put("file_path", resolved.toString());
        return next.apply(invocation.withInput(rewritten));
    }

    /**
     * Re-checks the real path: normalize() does not resolve symlinks, Windows 8.3 short names, or
     * NTFS ADS. For a not-yet-existing target (or root), compares the real paths of the nearest
     * existing ancestors on both sides — a new-file write through a symlinked directory must not
     * escape, while a brand-new workspace must still pass.
     *
     * @param resolved the lexically validated, normalized candidate path
     * @param root     the caller's sandbox root
     * @return whether the path stays inside the root after resolving real paths
     */
    private static boolean staysInsideRealBoundary(Path resolved, Path root) {
        try {
            return resolved.toRealPath().startsWith(root.toRealPath());
        } catch (IOException eExist) {
            Path existingTarget = nearestExistingAncestor(resolved);
            Path existingRoot = nearestExistingAncestor(root);
            if (existingTarget == null || existingRoot == null) {
                return false;
            }
            try {
                return existingTarget.toRealPath().startsWith(existingRoot.toRealPath());
            } catch (IOException eAncestor) {
                return false;
            }
        }
    }

    /**
     * Walks up until an existing ancestor is found.
     *
     * @param path the starting path
     * @return the nearest existing ancestor, or {@code null} at the filesystem root
     */
    private static Path nearestExistingAncestor(Path path) {
        Path current = path;
        while (current != null) {
            if (Files.exists(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Builds a model-actionable policy denial.
     *
     * @param modelMessage the denial reason shown to the model
     * @return a DENIED result carrying the interceptor identity prefix
     */
    private static Mono<ToolResultBlock> reject(String modelMessage) {
        return Mono.just(
                ToolResultBlock.text("PathSandbox: " + modelMessage)
                        .withState(ToolResultState.DENIED));
    }
}
