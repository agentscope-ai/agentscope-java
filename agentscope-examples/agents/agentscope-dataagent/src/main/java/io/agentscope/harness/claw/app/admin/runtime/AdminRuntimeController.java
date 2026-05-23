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
package io.agentscope.harness.claw.app.admin.runtime;

import io.agentscope.harness.claw.ClawBootstrap;
import io.agentscope.harness.claw.app.auth.UserStore;
import io.agentscope.harness.claw.app.session.UserWorkspaceProvisioner;
import io.agentscope.harness.claw.app.toolbus.WorkspaceTimelineReader;
import io.agentscope.harness.claw.app.util.InMemoryLogAppender;
import io.agentscope.harness.claw.channel.Channel;
import io.agentscope.harness.claw.channel.ChannelConfig;
import io.agentscope.harness.claw.channel.chatui.ChatUiChannel;
import io.agentscope.harness.claw.session.SessionAgentManager;
import io.agentscope.harness.claw.session.SessionEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read-only admin runtime endpoints consumed by the bundled admin console SPA.
 *
 * <ul>
 *   <li>{@code GET /api/admin/runtime/overview} — active sessions, users, agents, channels
 *   <li>{@code GET /api/admin/runtime/instances} — registered agent instances
 *   <li>{@code GET /api/admin/runtime/sessions} — all live sessions (flat)
 *   <li>{@code GET /api/admin/runtime/sessions/{key}} — single session detail
 *   <li>{@code GET /api/admin/runtime/sessions/{key}/tree} — recursive sub-agent tree
 *   <li>{@code GET /api/admin/runtime/channels} — registered channels with state
 *   <li>{@code GET /api/admin/runtime/logs} — recent log lines (SSE stream)
 * </ul>
 *
 * <p>All endpoints require {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/runtime")
public class AdminRuntimeController {

    private final ClawBootstrap clawBootstrap;
    private final SessionAgentManager sessionAgentManager;
    private final UserStore userStore;
    private final UserWorkspaceProvisioner userWorkspaceProvisioner;

    @Value("${claw.session.redis.enabled:false}")
    private boolean clusterMode;

    public AdminRuntimeController(
            ClawBootstrap clawBootstrap,
            UserStore userStore,
            UserWorkspaceProvisioner userWorkspaceProvisioner) {
        this.clawBootstrap = clawBootstrap;
        this.sessionAgentManager = clawBootstrap.gateway().sessionAgentManager();
        this.userStore = userStore;
        this.userWorkspaceProvisioner = userWorkspaceProvisioner;
    }

    @GetMapping("/overview")
    public Mono<OverviewDto> overview() {
        return Mono.fromCallable(
                () -> {
                    Collection<SessionEntry> sessions = sessionAgentManager.allSessions();
                    long activeSessions =
                            sessions.stream().filter(s -> s.lastActivityMs() > 0).count();
                    int userCount = userStore.listAll().size();
                    int agentCount = clawBootstrap.agents().size();
                    int channelCount = clawBootstrap.channelManager().getAllChannels().size();

                    List<ActivityEntry> recent =
                            sessions.stream()
                                    .sorted(
                                            Comparator.comparingLong(SessionEntry::lastActivityMs)
                                                    .reversed())
                                    .limit(10)
                                    .map(
                                            s ->
                                                    new ActivityEntry(
                                                            s.sessionKey(),
                                                            s.agentId(),
                                                            s.userId(),
                                                            s.lastActivityMs(),
                                                            s.kind() != null
                                                                    ? s.kind().name()
                                                                    : "?"))
                                    .toList();
                    return new OverviewDto(
                            (int) activeSessions, userCount, agentCount, channelCount, recent);
                });
    }

    @GetMapping("/instances")
    public Mono<List<AgentInstanceDto>> instances() {
        return Mono.fromCallable(
                () ->
                        clawBootstrap.agents().entrySet().stream()
                                .map(
                                        e ->
                                                new AgentInstanceDto(
                                                        e.getKey(),
                                                        e.getValue().getClass().getSimpleName()))
                                .toList());
    }

    @GetMapping("/sessions")
    public Mono<List<SessionDto>> sessions(@RequestParam(defaultValue = "100") int limit) {
        return Mono.fromCallable(
                () ->
                        sessionAgentManager.allSessions().stream()
                                .sorted(
                                        Comparator.comparingLong(SessionEntry::lastActivityMs)
                                                .reversed())
                                .limit(limit)
                                .map(
                                        s ->
                                                new SessionDto(
                                                        s.sessionKey(),
                                                        s.agentId(),
                                                        s.userId(),
                                                        s.kind() != null ? s.kind().name() : "?",
                                                        s.lastActivityMs(),
                                                        System.currentTimeMillis()
                                                                - s.lastActivityMs()))
                                .toList());
    }

    /** Detailed view of one session by sessionKey. Returns 404 if unknown. */
    @GetMapping("/sessions/{sessionKey}")
    public Mono<ResponseEntity<SessionDetailDto>> sessionDetail(
            @PathVariable("sessionKey") String sessionKey) {
        return Mono.fromCallable(
                () ->
                        sessionAgentManager
                                .getSession(sessionKey)
                                .map(AdminRuntimeController::toDetail)
                                .map(ResponseEntity::ok)
                                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    /**
     * Recursive sub-agent tree rooted at the given main session. Builds a depth-first tree of
     * {@link SessionEntry#spawnedBy()} relationships up to a safe cap. Useful for the admin UI
     * to visualise how a single user turn fanned out into sub-agent calls.
     */
    @GetMapping("/sessions/{sessionKey}/tree")
    public Mono<ResponseEntity<SessionTreeNode>> sessionTree(
            @PathVariable("sessionKey") String sessionKey) {
        return Mono.fromCallable(
                () -> {
                    SessionEntry root = sessionAgentManager.getSession(sessionKey).orElse(null);
                    if (root == null) {
                        return ResponseEntity.notFound().<SessionTreeNode>build();
                    }
                    Map<String, List<SessionEntry>> byParent = indexByParent();
                    SessionTreeNode tree = buildTree(root, byParent, 0, 8);
                    return ResponseEntity.ok(tree);
                });
    }

    @GetMapping("/sessions/{sessionKey}/workspace/events")
    public Mono<ResponseEntity<List<WorkspaceTimelineReader.MutationEntry>>> workspaceEvents(
            @PathVariable("sessionKey") String sessionKey,
            @RequestParam(required = false) Long since,
            @RequestParam(defaultValue = "200") int limit) {
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = sessionAgentManager.getSession(sessionKey).orElse(null);
                    if (entry == null) {
                        return ResponseEntity.notFound()
                                .<List<WorkspaceTimelineReader.MutationEntry>>build();
                    }
                    if (entry.userId() == null || entry.userId().isBlank()) {
                        return ResponseEntity.ok(List.<WorkspaceTimelineReader.MutationEntry>of());
                    }
                    return ResponseEntity.ok(
                            WorkspaceTimelineReader.readEvents(
                                    userWorkspaceProvisioner.resolveUserWorkspace(entry.userId()),
                                    entry.sessionId(),
                                    since,
                                    limit));
                });
    }

    @GetMapping("/channels")
    public Mono<List<ChannelDto>> channels() {
        return Mono.fromCallable(
                () -> {
                    Collection<Channel> all = clawBootstrap.channelManager().getAllChannels();
                    List<ChannelDto> result = new ArrayList<>();
                    for (Channel ch : all) {
                        ChannelConfig cfg = ch.config();
                        int queueSize = 0;
                        if (ch instanceof ChatUiChannel chatUi) {
                            queueSize = chatUi.outboundQueueSize();
                        }
                        result.add(
                                new ChannelDto(
                                        ch.channelId(),
                                        cfg.dmScope() != null ? cfg.dmScope().name() : "MAIN",
                                        cfg.defaultAgentId(),
                                        clawBootstrap.channelManager().isStarted(),
                                        queueSize,
                                        cfg.bindings() != null ? cfg.bindings().size() : 0));
                    }
                    return result;
                });
    }

    @GetMapping(value = "/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamLogs(@RequestParam(defaultValue = "100") int tail) {
        List<String> history = InMemoryLogAppender.recentLines(tail);
        Flux<String> historical = Flux.fromIterable(history);
        Flux<String> live = InMemoryLogAppender.liveStream();
        Flux<String> body = Flux.concat(historical, live);
        if (clusterMode) {
            String warning =
                    "[CLUSTER WARNING] In-memory log SSE only reflects this replica. For"
                            + " cluster-wide logs, point your dashboard at the external log"
                            + " aggregator (Loki / CloudWatch / Datadog).";
            return Flux.concat(Flux.just(warning), body);
        }
        return body;
    }

    // -----------------------------------------------------------------
    //  Tree-building helpers
    // -----------------------------------------------------------------

    private Map<String, List<SessionEntry>> indexByParent() {
        Map<String, List<SessionEntry>> byParent = new HashMap<>();
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            String parent = e.spawnedBy();
            if (parent != null && !parent.isBlank()) {
                byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(e);
            }
        }
        return byParent;
    }

    private static SessionTreeNode buildTree(
            SessionEntry node, Map<String, List<SessionEntry>> byParent, int depth, int maxDepth) {
        List<SessionTreeNode> children = new ArrayList<>();
        if (depth < maxDepth) {
            List<SessionEntry> kids = byParent.get(node.sessionKey());
            if (kids != null) {
                kids.sort(Comparator.comparingLong(SessionEntry::createdAtMs));
                for (SessionEntry kid : kids) {
                    children.add(buildTree(kid, byParent, depth + 1, maxDepth));
                }
            }
        }
        return new SessionTreeNode(toDetail(node), children);
    }

    private static SessionDetailDto toDetail(SessionEntry e) {
        return new SessionDetailDto(
                e.sessionKey(),
                e.agentId(),
                e.sessionId(),
                e.label(),
                e.kind() != null ? e.kind().name() : "?",
                e.spawnedBy(),
                e.spawnDepth(),
                e.userId(),
                e.gateKey(),
                e.sessionFilePath(),
                e.createdAtMs(),
                e.lastActivityMs(),
                System.currentTimeMillis() - e.lastActivityMs());
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    public record OverviewDto(
            int activeSessionCount,
            int totalUserCount,
            int registeredAgentCount,
            int registeredChannelCount,
            List<ActivityEntry> recentActivity) {}

    public record ActivityEntry(
            String sessionKey, String agentId, String userId, long lastActivityMs, String kind) {}

    public record AgentInstanceDto(String agentId, String className) {}

    public record SessionDto(
            String sessionKey,
            String agentId,
            String userId,
            String kind,
            long lastActivityMs,
            long idleMs) {}

    public record ChannelDto(
            String channelId,
            String dmScope,
            String defaultAgentId,
            boolean started,
            int outboundQueueSize,
            int bindingCount) {}

    /** Full-fidelity session detail used by both /sessions/{key} and tree nodes. */
    public record SessionDetailDto(
            String sessionKey,
            String agentId,
            String sessionId,
            String label,
            String kind,
            String spawnedBy,
            int spawnDepth,
            String userId,
            String gateKey,
            String sessionFilePath,
            long createdAtMs,
            long lastActivityMs,
            long idleMs) {}

    /** Recursive node returned by /sessions/{key}/tree. */
    public record SessionTreeNode(SessionDetailDto session, List<SessionTreeNode> children) {}
}
