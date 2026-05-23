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
package io.agentscope.harness.claw.app.toolbus;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Captures workspace file mutations performed by built-in HarnessAgent tools ({@code write_file},
 * {@code edit_file}) and appends one JSONL line per mutation to {@code
 * sessions/<sessionId>.workspace.jsonl} inside the agent's workspace root.
 *
 * <p>Because each tenant now runs in their own {@link HarnessAgent} whose workspace is pinned to
 * {@code .agentscope/users/{userId}/workspace/}, the timeline file lands at {@code
 * .agentscope/users/{userId}/workspace/sessions/<sessionId>.workspace.jsonl}. The user identity is
 * also recorded in each JSONL entry for cross-user analytics by admins.
 *
 * <p>One instance is shared across all agents — per-call state is keyed by tool-call-id in {@link
 * #pending} and cleared on the matching {@link PostActingEvent}.
 *
 * <p>Bash/sandbox tools are intentionally out of scope for this minimal cut: their writes will not
 * appear in the timeline. A future generic walker (Phase 2.b) can close that gap.
 */
public class WorkspaceMutationHook implements Hook, RuntimeContextAware {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMutationHook.class);

    /** Tools whose {@code path} input identifies a workspace mutation. */
    private static final java.util.Set<String> MUTATING_TOOLS =
            java.util.Set.of("write_file", "edit_file");

    /** Don't hash files larger than this — record size only. */
    private static final long MAX_HASH_BYTES = 1L << 20; // 1 MiB

    private final Map<String, PreState> pending = new ConcurrentHashMap<>();

    private RuntimeContext runtimeContext;

    @Override
    public void setRuntimeContext(RuntimeContext ctx) {
        this.runtimeContext = ctx;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            if (event instanceof PreActingEvent pre) {
                handlePre(pre);
            } else if (event instanceof PostActingEvent post) {
                handlePost(post);
            }
        } catch (Exception e) {
            log.debug("Workspace mutation hook failed: {}", e.getMessage());
        }
        return Mono.just(event);
    }

    private void handlePre(PreActingEvent pre) {
        ToolUseBlock use = pre.getToolUse();
        if (use == null || use.getId() == null) return;
        if (!MUTATING_TOOLS.contains(use.getName())) return;

        WorkspaceManager wm = resolveWorkspaceManager(pre.getAgent());
        if (wm == null) return;

        Path target = resolveWorkspacePath(wm, use);
        if (target == null) return;

        pending.put(use.getId(), snapshot(target));
    }

    private void handlePost(PostActingEvent post) {
        ToolUseBlock use = post.getToolUse();
        if (use == null || use.getId() == null) return;
        PreState before = pending.remove(use.getId());
        if (!MUTATING_TOOLS.contains(use.getName())) return;

        WorkspaceManager wm = resolveWorkspaceManager(post.getAgent());
        if (wm == null) return;

        Path target = resolveWorkspacePath(wm, use);
        if (target == null) return;

        PreState after = snapshot(target);
        if (before == null) before = PreState.absent();

        String kind = classify(before, after);
        if ("NOOP".equals(kind)) return;

        SessionContext sc = resolveSession();
        if (sc == null) {
            log.debug("Skipping mutation log: no session context for {}", use.getName());
            return;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", System.currentTimeMillis());
        entry.put("sessionKey", sc.sessionKey);
        entry.put("userId", sc.userId);
        entry.put("agentId", sc.agentId);
        entry.put("sessionId", sc.sessionId);
        entry.put("toolCallId", use.getId());
        entry.put("toolName", use.getName());
        entry.put("path", wm.getWorkspace().relativize(target).toString().replace('\\', '/'));
        entry.put("kind", kind);
        entry.put("preHash", before.hash);
        entry.put("postHash", after.hash);
        entry.put("preSize", before.size);
        entry.put("postSize", after.size);

        String line = JsonUtils.getJsonCodec().toJson(entry) + "\n";
        // Relative to the agent's workspace root — which is the per-user workspace under the
        // new tenancy model, so the file lands at
        // .agentscope/users/{userId}/workspace/sessions/{sessionId}.workspace.jsonl.
        String relative = "sessions/" + sc.sessionId + ".workspace.jsonl";
        try {
            wm.appendUtf8WorkspaceRelative(relative, line);
        } catch (Exception e) {
            log.debug("Failed to append workspace mutation log: {}", e.getMessage());
        }
    }

    private static Path resolveWorkspacePath(WorkspaceManager wm, ToolUseBlock use) {
        Map<String, Object> input = use.getInput();
        if (input == null) return null;
        Object raw = input.get("path");
        if (!(raw instanceof String s) || s.isBlank()) return null;
        Path resolved = wm.getWorkspace().resolve(s).normalize();
        if (!resolved.startsWith(wm.getWorkspace())) return null;
        return resolved;
    }

    private static WorkspaceManager resolveWorkspaceManager(Agent agent) {
        if (agent instanceof HarnessAgent ha) return ha.getWorkspaceManager();
        return null;
    }

    private SessionContext resolveSession() {
        RuntimeContext ctx = this.runtimeContext;
        if (ctx == null) return null;
        String sessionId = ctx.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        SessionKey key = ctx.getSessionKey();
        String sessionKeyStr = key != null ? key.toIdentifier() : sessionId;
        String agentId = parseAgentId(sessionKeyStr);
        if (agentId == null) agentId = "unknown";
        String userId = ctx.getUserId();
        return new SessionContext(sessionKeyStr, userId, agentId, sessionId);
    }

    /**
     * Session keys produced by claw look like {@code <agentId>:<sessionId>} (see {@code
     * SessionAgentManager#composeSessionKey}). Falls back to null on unfamiliar shapes.
     */
    private static String parseAgentId(String sessionKey) {
        if (sessionKey == null) return null;
        int colon = sessionKey.indexOf(':');
        if (colon <= 0) return null;
        return sessionKey.substring(0, colon);
    }

    private static PreState snapshot(Path file) {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return PreState.absent();
        }
        try {
            long size = Files.size(file);
            String hash = size <= MAX_HASH_BYTES ? sha256(file) : null;
            return new PreState(hash, size);
        } catch (Exception e) {
            return PreState.absent();
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String classify(PreState before, PreState after) {
        boolean had = before.exists();
        boolean has = after.exists();
        if (!had && has) return "CREATE";
        if (had && !has) return "DELETE";
        if (!had && !has) return "NOOP";
        // both exist — same hash means no change
        if (before.hash != null && before.hash.equals(after.hash) && before.size == after.size) {
            return "NOOP";
        }
        return "EDIT";
    }

    private record PreState(String hash, long size) {
        static PreState absent() {
            return new PreState(null, -1L);
        }

        boolean exists() {
            return size >= 0;
        }
    }

    private record SessionContext(
            String sessionKey, String userId, String agentId, String sessionId) {}
}
