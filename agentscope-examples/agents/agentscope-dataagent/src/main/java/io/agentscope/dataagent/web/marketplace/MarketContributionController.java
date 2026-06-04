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
package io.agentscope.dataagent.web.marketplace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * User-facing REST surface for nominating workspace artifacts for promotion to the shared
 * workspace via admin approval.
 *
 * <ul>
 *   <li>{@code POST /api/me/contributions} — submit a new nomination whose payload the client
 *       already harvested itself (FileEntry list in the request body)
 *   <li>{@code POST /api/me/contributions/from-workspace} — submit by reference: server reads
 *       file content from the caller's sandbox using the listed source paths
 *   <li>{@code GET  /api/me/contributions} — list the current user's submissions
 * </ul>
 */
@RestController
@RequestMapping("/api/me/contributions")
public class MarketContributionController {

    private final MarketContributionService service;
    private final WorkspaceManagerFactory workspaceFactory;

    public MarketContributionController(
            MarketContributionService service, WorkspaceManagerFactory workspaceFactory) {
        this.service = service;
        this.workspaceFactory = workspaceFactory;
    }

    @PostMapping
    public Mono<ContributionView> submit(@RequestBody SubmitRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    try {
                        ContributionEntity saved =
                                service.submit(
                                        userId,
                                        req.sourceAgentId(),
                                        req.targetAgentId(),
                                        req.targetType(),
                                        req.targetPath(),
                                        req.rationale(),
                                        req.payload());
                        return ContributionView.from(saved);
                    } catch (IllegalArgumentException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
                    }
                });
    }

    @PostMapping("/from-workspace")
    public Mono<ContributionView> submitFromWorkspace(
            @RequestBody FromWorkspaceRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    try {
                        List<FileEntry> payload =
                                harvestFromUserSandbox(
                                        userId,
                                        req.sourceAgentId(),
                                        req.targetType(),
                                        req.sourcePaths());
                        ContributionEntity saved =
                                service.submit(
                                        userId,
                                        req.sourceAgentId(),
                                        req.targetAgentId(),
                                        req.targetType(),
                                        req.targetPath(),
                                        req.rationale(),
                                        payload);
                        return ContributionView.from(saved);
                    } catch (IllegalArgumentException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
                    }
                });
    }

    @GetMapping
    public Mono<List<ContributionView>> listMine(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> service.listMine(userId).stream().map(ContributionView::from).toList());
    }

    private List<FileEntry> harvestFromUserSandbox(
            String userId, String sourceAgentId, String targetType, List<String> sourcePaths) {
        if (sourceAgentId == null || sourceAgentId.isBlank()) {
            throw new IllegalArgumentException("sourceAgentId is required");
        }
        if (sourcePaths == null || sourcePaths.isEmpty()) {
            throw new IllegalArgumentException("sourcePaths must contain at least one entry");
        }
        boolean isSkillBundle = ContributionEntity.TARGET_SKILL.equals(targetType);
        if (!isSkillBundle && sourcePaths.size() != 1) {
            throw new IllegalArgumentException(
                    "target_type "
                            + targetType
                            + " requires exactly one source path (got "
                            + sourcePaths.size()
                            + ")");
        }
        WorkspaceManager wm = workspaceFactory.forAgent(userId, sourceAgentId);
        RuntimeContext rc = RuntimeContext.builder().userId(userId).build();
        List<FileEntry> out = new ArrayList<>(sourcePaths.size());
        for (String path : sourcePaths) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("sourcePaths entries must be non-blank");
            }
            String content = wm.readManagedWorkspaceFileUtf8(rc, path);
            if (content == null || content.isEmpty()) {
                throw new IllegalArgumentException("source file is empty or unreadable: " + path);
            }
            String relPath = isSkillBundle ? Paths.get(path).getFileName().toString() : "";
            out.add(new FileEntry(relPath, content));
        }
        return out;
    }

    public record SubmitRequest(
            String sourceAgentId,
            String targetAgentId,
            String targetType,
            String targetPath,
            String rationale,
            List<FileEntry> payload) {}

    public record FromWorkspaceRequest(
            String sourceAgentId,
            String targetAgentId,
            String targetType,
            String targetPath,
            String rationale,
            List<String> sourcePaths) {}

    public record ContributionView(
            long id,
            String status,
            String sourceUserId,
            String sourceAgentId,
            String targetAgentId,
            String targetType,
            String targetPath,
            String rationale,
            String reviewerUserId,
            String reviewerNote,
            long createdAt,
            long updatedAt) {
        public static ContributionView from(ContributionEntity e) {
            return new ContributionView(
                    e.getId(),
                    e.getStatus(),
                    e.getSourceUserId(),
                    e.getSourceAgentId(),
                    e.getTargetAgentId(),
                    e.getTargetType(),
                    e.getTargetPath(),
                    e.getRationale(),
                    e.getReviewerUserId(),
                    e.getReviewerNote(),
                    e.getCreatedAt(),
                    e.getUpdatedAt());
        }
    }
}
