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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import io.agentscope.dataagent.web.persistence.jpa.ContributionRepository;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
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
 * <p>Users nominate one or more workspace files (skills, sub-agents, memory snippets, AGENTS.md,
 * knowledge documents) from their isolated workspace; admins approve, and the snapshot is
 * materialised under {@code ${dataagentHome}/shared/agents/<targetAgentId>/<type>/<path>} so the
 * content becomes immediately visible to every user of that agent through the sandbox projection.
 *
 * <p>The shared-layer layout is per-agent ({@code shared/agents/<agentId>/}) to mirror the
 * {@code IsolationScope.AGENT} namespace used by the harness's
 * {@link io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec}, so user-created agents
 * do not leak shared content into one another.
 *
 * <p>Approval optionally honours an {@code approvedPayload} edited by the reviewer; the original
 * {@code payload} is always retained for audit.
 */
@Service
public class MarketContributionService {

    private static final Logger log = LoggerFactory.getLogger(MarketContributionService.class);

    private static final Set<String> ALLOWED_TARGETS =
            Set.of(
                    ContributionEntity.TARGET_SKILL,
                    ContributionEntity.TARGET_SUBAGENT,
                    ContributionEntity.TARGET_MEMORY,
                    ContributionEntity.TARGET_AGENTS_MD,
                    ContributionEntity.TARGET_KNOWLEDGE);

    private static final TypeReference<List<FileEntry>> FILE_ENTRY_LIST_TYPE =
            new TypeReference<>() {};

    private final ContributionRepository repository;
    private final UserSandboxRegistry sandboxRegistry;
    private final ObjectMapper objectMapper;
    private final Path sharedRoot;

    public MarketContributionService(
            ContributionRepository repository,
            DataAgentBootstrap bootstrap,
            UserSandboxRegistry sandboxRegistry,
            ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sandboxRegistry = Objects.requireNonNull(sandboxRegistry, "sandboxRegistry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.sharedRoot = bootstrap.cwd().resolve("shared");
    }

    /**
     * Records a new pending contribution. The {@code payload} is taken at face value — callers
     * are responsible for having harvested file contents from the source user's workspace.
     *
     * @param targetAgentId when null/blank, defaults to {@code sourceAgentId}.
     */
    @Transactional
    public ContributionEntity submit(
            String sourceUserId,
            String sourceAgentId,
            String targetAgentId,
            String targetType,
            String targetPath,
            String rationale,
            List<FileEntry> payload) {
        String resolvedTargetAgentId =
                (targetAgentId == null || targetAgentId.isBlank()) ? sourceAgentId : targetAgentId;
        validate(sourceUserId, resolvedTargetAgentId, targetType, targetPath, payload);
        // Probe target resolution at submit time so obviously-invalid paths fail fast instead of
        // sitting in PENDING until an admin tries to approve them.
        resolveTargetFiles(resolvedTargetAgentId, targetType, targetPath, payload);

        long now = System.currentTimeMillis();
        ContributionEntity entity = new ContributionEntity();
        entity.setStatus(ContributionEntity.STATUS_PENDING);
        entity.setSourceUserId(sourceUserId);
        entity.setSourceAgentId(sourceAgentId);
        entity.setTargetAgentId(resolvedTargetAgentId);
        entity.setTargetType(targetType);
        entity.setTargetPath(targetPath);
        entity.setRationale(rationale);
        entity.setPayload(serializePayload(payload));
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

    @Transactional(readOnly = true)
    public ContributionEntity get(long id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("contribution not found: " + id));
    }

    /** Returns the payload (or approvedPayload, when present and non-empty) as a FileEntry list. */
    public List<FileEntry> readPayload(ContributionEntity entity) {
        String json = entity.getApprovedPayload();
        if (json == null || json.isBlank()) {
            json = entity.getPayload();
        }
        return deserializePayload(json);
    }

    public List<FileEntry> readOriginalPayload(ContributionEntity entity) {
        return deserializePayload(entity.getPayload());
    }

    public List<FileEntry> readApprovedPayload(ContributionEntity entity) {
        String json = entity.getApprovedPayload();
        return (json == null || json.isBlank()) ? null : deserializePayload(json);
    }

    /**
     * Approves the contribution, materialises its (possibly admin-edited) payload under
     * {@code ${dataagentHome}/shared/agents/<targetAgentId>/<type>/<path>}, and transitions to
     * {@code APPROVED}.
     */
    @Transactional
    public ContributionEntity approve(
            long id, String reviewerUserId, String reviewerNote, List<FileEntry> approvedPayload) {
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

        List<FileEntry> toWrite =
                approvedPayload != null ? approvedPayload : deserializePayload(entity.getPayload());
        if (toWrite.isEmpty()) {
            throw new IllegalArgumentException("contribution payload is empty");
        }
        List<TargetFile> targets =
                resolveTargetFiles(
                        entity.getTargetAgentId(),
                        entity.getTargetType(),
                        entity.getTargetPath(),
                        toWrite);
        try {
            for (TargetFile tf : targets) {
                Files.createDirectories(tf.path().getParent());
                Files.writeString(tf.path(), tf.content(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        log.info(
                "Approved contribution id={} ({} -> agent={} files={}) by reviewer={}",
                id,
                entity.getTargetType(),
                entity.getTargetAgentId(),
                targets.size(),
                reviewerUserId);

        entity.setStatus(ContributionEntity.STATUS_APPROVED);
        entity.setReviewerUserId(reviewerUserId);
        entity.setReviewerNote(reviewerNote);
        if (approvedPayload != null) {
            entity.setApprovedPayload(serializePayload(approvedPayload));
        }
        entity.setUpdatedAt(System.currentTimeMillis());
        ContributionEntity saved = repository.save(entity);

        // Force every existing sandbox of the target agent to be torn down so the next borrow
        // re-creates a container that picks up the new files from the shared projection.
        sandboxRegistry.invalidate(null, entity.getTargetAgentId());
        return saved;
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

    /**
     * Resolves the on-disk targets for a contribution. Single-file target types
     * (subagent / memory / agents_md / knowledge) require exactly one FileEntry. Skills allow one
     * or more entries; an entry with empty {@code relPath} maps to {@code SKILL.md}.
     */
    private List<TargetFile> resolveTargetFiles(
            String targetAgentId, String targetType, String targetPath, List<FileEntry> entries) {
        Path agentRoot = sharedRoot.resolve("agents").resolve(targetAgentId).normalize();
        if (!agentRoot.startsWith(sharedRoot.normalize())) {
            throw new IllegalArgumentException("targetAgentId escapes shared/: " + targetAgentId);
        }
        return switch (targetType) {
            case ContributionEntity.TARGET_SKILL ->
                    resolveSkillTargets(agentRoot, targetPath, entries);
            case ContributionEntity.TARGET_SUBAGENT ->
                    List.of(singleFileTarget(agentRoot.resolve("subagents"), targetPath, entries));
            case ContributionEntity.TARGET_MEMORY ->
                    List.of(singleFileTarget(agentRoot.resolve("memory"), targetPath, entries));
            case ContributionEntity.TARGET_AGENTS_MD ->
                    List.of(agentsMdTarget(agentRoot, targetPath, entries));
            case ContributionEntity.TARGET_KNOWLEDGE ->
                    List.of(singleFileTarget(agentRoot.resolve("knowledge"), targetPath, entries));
            default -> throw new IllegalArgumentException("unsupported targetType: " + targetType);
        };
    }

    private List<TargetFile> resolveSkillTargets(
            Path agentRoot, String bundleName, List<FileEntry> entries) {
        Path bundleDir = agentRoot.resolve("skills").resolve(bundleName).normalize();
        Path skillsRoot = agentRoot.resolve("skills").normalize();
        if (!bundleDir.startsWith(skillsRoot)) {
            throw new IllegalArgumentException("skill bundle path escapes skills/: " + bundleName);
        }
        java.util.List<TargetFile> out = new java.util.ArrayList<>(entries.size());
        for (FileEntry fe : entries) {
            String rel = fe.relPath();
            if (rel.isEmpty()) {
                // Default file for bare skill submissions.
                rel = "SKILL.md";
            }
            if (rel.startsWith("/") || rel.contains("..")) {
                throw new IllegalArgumentException("skill file relPath must be clean: " + rel);
            }
            Path resolved = bundleDir.resolve(rel).normalize();
            if (!resolved.startsWith(bundleDir)) {
                throw new IllegalArgumentException("skill file relPath escapes bundle dir: " + rel);
            }
            out.add(new TargetFile(resolved, fe.content()));
        }
        return out;
    }

    private TargetFile singleFileTarget(Path typeDir, String targetPath, List<FileEntry> entries) {
        if (entries.size() != 1) {
            throw new IllegalArgumentException(
                    "single-file target requires exactly one file entry (got "
                            + entries.size()
                            + ")");
        }
        Path normalizedTypeDir = typeDir.normalize();
        Path resolved = normalizedTypeDir.resolve(targetPath).normalize();
        if (!resolved.startsWith(normalizedTypeDir)) {
            throw new IllegalArgumentException(
                    "targetPath escapes the type directory: " + targetPath);
        }
        return new TargetFile(resolved, entries.get(0).content());
    }

    private TargetFile agentsMdTarget(Path agentRoot, String targetPath, List<FileEntry> entries) {
        if (entries.size() != 1) {
            throw new IllegalArgumentException("AGENTS.md target requires exactly one file entry");
        }
        if (!"AGENTS.md".equals(targetPath)) {
            throw new IllegalArgumentException(
                    "AGENTS.md target requires targetPath=\"AGENTS.md\" (got " + targetPath + ")");
        }
        return new TargetFile(agentRoot.resolve("AGENTS.md"), entries.get(0).content());
    }

    private String serializePayload(List<FileEntry> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize payload: " + e.getMessage(), e);
        }
    }

    private List<FileEntry> deserializePayload(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, FILE_ENTRY_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to deserialize payload (corrupt row?): " + e.getMessage(), e);
        }
    }

    private static void validate(
            String sourceUserId,
            String targetAgentId,
            String targetType,
            String targetPath,
            List<FileEntry> payload) {
        if (sourceUserId == null || sourceUserId.isBlank()) {
            throw new IllegalArgumentException("sourceUserId is required");
        }
        if (targetAgentId == null || targetAgentId.isBlank()) {
            throw new IllegalArgumentException(
                    "targetAgentId is required (and sourceAgentId fallback was also blank)");
        }
        if (targetAgentId.contains("/")
                || targetAgentId.contains("\\")
                || targetAgentId.contains("..")) {
            throw new IllegalArgumentException(
                    "targetAgentId must not contain path separators or '..': " + targetAgentId);
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
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("payload must contain at least one file entry");
        }
        for (FileEntry fe : payload) {
            if (fe == null) {
                throw new IllegalArgumentException("payload entries must not be null");
            }
            if (fe.content() == null) {
                throw new IllegalArgumentException("file entry content must not be null");
            }
        }
    }

    private record TargetFile(Path path, String content) {}
}
