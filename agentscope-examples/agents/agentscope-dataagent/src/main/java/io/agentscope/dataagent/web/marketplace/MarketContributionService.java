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

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import io.agentscope.dataagent.web.persistence.jpa.ContributionRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service backing the user-contribution + admin-approval flow.
 *
 * <p>Users nominate skills / subagents / memory snippets from their isolated workspace; admins
 * approve, and the verbatim snapshot is materialized under
 * {@code ${dataagentHome}/shared/<type>/<path>} so the content becomes immediately visible to
 * every tenant through their composite-FS lower layer.
 *
 * <p>This is deliberately the simplest workable form — no version history, no draft / re-submit,
 * no per-target ACLs. The intent is to land a working flow today; a follow-up phase can replace
 * {@code LocalApprovalMarketplace} with a Git-backed implementation if reviews need to become PRs.
 */
@Service
public class MarketContributionService {

    private static final Logger log = LoggerFactory.getLogger(MarketContributionService.class);

    private static final Set<String> ALLOWED_TARGETS =
            Set.of(
                    ContributionEntity.TARGET_SKILL,
                    ContributionEntity.TARGET_SUBAGENT,
                    ContributionEntity.TARGET_MEMORY);

    private final ContributionRepository repository;
    private final Path sharedRoot;

    public MarketContributionService(
            ContributionRepository repository, DataAgentBootstrap bootstrap) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sharedRoot = bootstrap.cwd().resolve("shared");
    }

    /**
     * Records a new pending contribution and returns the persisted row. The payload is taken at
     * face value — callers (typically {@code MarketContributionController}) are responsible for
     * having harvested it from the user's own workspace.
     */
    @Transactional
    public ContributionEntity submit(
            String sourceUserId,
            String sourceAgentId,
            String targetType,
            String targetPath,
            String rationale,
            String payload) {
        validate(sourceUserId, targetType, targetPath, payload);
        long now = System.currentTimeMillis();
        ContributionEntity entity = new ContributionEntity();
        entity.setStatus(ContributionEntity.STATUS_PENDING);
        entity.setSourceUserId(sourceUserId);
        entity.setSourceAgentId(sourceAgentId);
        entity.setTargetType(targetType);
        entity.setTargetPath(targetPath);
        entity.setRationale(rationale);
        entity.setPayload(payload);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<ContributionEntity> listByStatus(String status) {
        return repository.findAllByStatusOrderByCreatedAtDesc(
                status == null ? ContributionEntity.STATUS_PENDING : status);
    }

    @Transactional(readOnly = true)
    public List<ContributionEntity> listMine(String sourceUserId) {
        return repository.findAllBySourceUserIdOrderByCreatedAtDesc(sourceUserId);
    }

    /**
     * Approves the contribution, materializes its payload under
     * {@code ${dataagentHome}/shared/<type>/<path>}, and transitions to {@code APPROVED}.
     */
    @Transactional
    public ContributionEntity approve(long id, String reviewerUserId, String reviewerNote) {
        ContributionEntity entity =
                repository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "contribution not found: " + id));
        if (!ContributionEntity.STATUS_PENDING.equals(entity.getStatus())) {
            throw new IllegalStateException(
                    "contribution " + id + " is not pending (status=" + entity.getStatus() + ")");
        }
        Path target = resolveTargetPath(entity.getTargetType(), entity.getTargetPath());
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, entity.getPayload(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info(
                "Approved contribution id={} ({} -> {}) by reviewer={}",
                id,
                entity.getTargetType(),
                target,
                reviewerUserId);
        entity.setStatus(ContributionEntity.STATUS_APPROVED);
        entity.setReviewerUserId(reviewerUserId);
        entity.setReviewerNote(reviewerNote);
        entity.setUpdatedAt(System.currentTimeMillis());
        return repository.save(entity);
    }

    /** Rejects the contribution with a reason; no filesystem change. */
    @Transactional
    public ContributionEntity reject(long id, String reviewerUserId, String reason) {
        ContributionEntity entity =
                repository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "contribution not found: " + id));
        if (!ContributionEntity.STATUS_PENDING.equals(entity.getStatus())) {
            throw new IllegalStateException(
                    "contribution " + id + " is not pending (status=" + entity.getStatus() + ")");
        }
        entity.setStatus(ContributionEntity.STATUS_REJECTED);
        entity.setReviewerUserId(reviewerUserId);
        entity.setReviewerNote(reason);
        entity.setUpdatedAt(System.currentTimeMillis());
        return repository.save(entity);
    }

    private Path resolveTargetPath(String targetType, String targetPath) {
        Path subdir =
                switch (targetType) {
                    case ContributionEntity.TARGET_SKILL -> sharedRoot.resolve("skills");
                    case ContributionEntity.TARGET_SUBAGENT -> sharedRoot.resolve("subagents");
                    case ContributionEntity.TARGET_MEMORY -> sharedRoot.resolve("memory");
                    default ->
                            throw new IllegalArgumentException(
                                    "unsupported targetType: " + targetType);
                };
        Path resolved = subdir.resolve(targetPath).normalize();
        if (!resolved.startsWith(subdir.normalize())) {
            throw new IllegalArgumentException(
                    "targetPath escapes the shared/" + targetType + " directory: " + targetPath);
        }
        if (ContributionEntity.TARGET_SKILL.equals(targetType)
                && !targetPath.contains("/")
                && !targetPath.contains("\\")) {
            // For a bare skill name, write the canonical SKILL.md inside that directory.
            return resolved.resolve("SKILL.md");
        }
        return resolved;
    }

    private static void validate(
            String sourceUserId, String targetType, String targetPath, String payload) {
        if (sourceUserId == null || sourceUserId.isBlank()) {
            throw new IllegalArgumentException("sourceUserId is required");
        }
        if (targetType == null || !ALLOWED_TARGETS.contains(targetType)) {
            throw new IllegalArgumentException(
                    "targetType must be one of " + ALLOWED_TARGETS + " (got " + targetType + ")");
        }
        if (targetPath == null || targetPath.isBlank()) {
            throw new IllegalArgumentException("targetPath is required");
        }
        if (targetPath.startsWith("/") || targetPath.contains("..")) {
            throw new IllegalArgumentException("targetPath must be a clean relative path");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload is required");
        }
    }
}
