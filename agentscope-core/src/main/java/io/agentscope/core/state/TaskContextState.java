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
package io.agentscope.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Session task aggregate carried and persisted by {@link AgentState}. Todo progress and
 * requirements share one revision; neither implies independently verified task completion.
 * Mutations should run inside the owning agent call/session transaction.
 */
public final class TaskContextState {

    private final List<Task> tasks;
    private long revision;
    private final List<TaskRequirement> requirements;
    private Scope scope;
    private long contractVersion;
    private String subjectVersion;
    private final List<TaskVerification> verifications;

    /** Application task identity, not an automatically allocated control-plane task. */
    public record Scope(String taskId, String objective, String sourceRef) {
        public Scope {
            TaskRequirement.requireText(taskId, "task ID", 200);
            TaskRequirement.requireText(objective, "objective", 4000);
            TaskRequirement.requireText(sourceRef, "objective source", 2000);
        }
    }

    @JsonProperty("revision")
    public synchronized long getRevision() {
        return revision;
    }

    /** Construct an empty context. */
    public TaskContextState() {
        this(null);
    }

    public TaskContextState(List<Task> tasks) {
        this(tasks, List.of(), 0, null, 0, null, List.of());
    }

    @JsonCreator
    public TaskContextState(
            @JsonProperty("tasks") List<Task> tasks,
            @JsonProperty("requirements") List<TaskRequirement> requirements,
            @JsonProperty("revision") long revision,
            @JsonProperty("scope") Scope scope,
            @JsonProperty("contractVersion") long contractVersion,
            @JsonProperty("subjectVersion") String subjectVersion,
            @JsonProperty("verifications") List<TaskVerification> verifications) {
        if (revision < 0) throw new IllegalArgumentException("Negative task revision");
        this.tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
        this.requirements =
                new ArrayList<>(requirements == null ? List.of() : List.copyOf(requirements));
        if (this.requirements.size() > 64
                || this.requirements.stream().map(TaskRequirement::id).distinct().count()
                        != this.requirements.size()) {
            throw new IllegalArgumentException(
                    "At most 64 uniquely identified requirements are allowed");
        }
        this.revision = revision;
        this.scope = scope;
        if (contractVersion < 0) throw new IllegalArgumentException("Negative contract version");
        if (subjectVersion != null)
            TaskRequirement.requireText(subjectVersion, "subject version", 2000);
        this.contractVersion = contractVersion;
        this.subjectVersion = subjectVersion;
        this.verifications =
                new ArrayList<>(verifications == null ? List.of() : List.copyOf(verifications));
        if (this.verifications.size() > 64
                || this.verifications.stream()
                                .map(TaskVerification::requirementId)
                                .distinct()
                                .count()
                        != this.verifications.size()) {
            throw new IllegalArgumentException(
                    "At most one current report per requirement is allowed");
        }
    }

    @JsonProperty("tasks")
    public synchronized List<Task> getTasks() {
        return List.copyOf(tasks);
    }

    @JsonProperty("requirements")
    public synchronized List<TaskRequirement> getRequirements() {
        return List.copyOf(requirements);
    }

    @JsonProperty("scope")
    public synchronized Scope getScope() {
        return scope;
    }

    /** Explicit trusted task switch. Clears the old task's progress and requirements together. */
    public synchronized void beginTask(Scope next, long expectedRevision) {
        checkRevision(expectedRevision);
        Objects.requireNonNull(next, "scope");
        if (next.equals(scope)) return;
        if (scope != null && scope.taskId().equals(next.taskId())) {
            throw new IllegalArgumentException(
                    "Use a new task identity when changing the objective");
        }
        scope = next;
        tasks.clear();
        requirements.clear();
        verifications.clear();
        subjectVersion = null;
        contractVersion++;
        revision++;
    }

    /** Atomically replaces progress without overwriting requirements. */
    public synchronized void replaceTasks(List<Task> replacement, long expectedRevision) {
        checkRevision(expectedRevision);
        List<Task> validated = List.copyOf(replacement);
        tasks.clear();
        tasks.addAll(validated);
        revision++;
    }

    /** Source references supplied here are untrusted claims until explicitly confirmed. */
    public synchronized TaskRequirement propose(
            TaskRequirement.Kind kind, String text, String sourceRef) {
        var candidate =
                new TaskRequirement(
                        UUID.randomUUID().toString(),
                        kind,
                        text,
                        sourceRef,
                        TaskRequirement.Status.CANDIDATE,
                        null);
        for (var existing : requirements) {
            if (existing.kind() == kind
                    && existing.text().equals(text)
                    && existing.proposedSourceRef().equals(sourceRef)) return existing;
        }
        if (requirements.size() >= 64)
            throw new IllegalStateException("Task requirement limit reached");
        requirements.add(candidate);
        revision++;
        return candidate;
    }

    /** Trusted application API. Not registered as a model-callable tool. */
    public synchronized void decide(
            String id,
            TaskRequirement.Status status,
            TaskRequirement.Decision decision,
            long expectedRevision) {
        checkRevision(expectedRevision);
        if (status == null || status == TaskRequirement.Status.CANDIDATE) {
            throw new IllegalArgumentException("Decision must confirm or reject");
        }
        Objects.requireNonNull(decision, "decision");
        for (int i = 0; i < requirements.size(); i++) {
            var existing = requirements.get(i);
            if (!existing.id().equals(id)) continue;
            if (existing.status() == status && decision.equals(existing.decision())) return;
            requirements.set(
                    i,
                    new TaskRequirement(
                            existing.id(),
                            existing.kind(),
                            existing.text(),
                            existing.proposedSourceRef(),
                            status,
                            decision));
            revision++;
            contractVersion++;
            return;
        }
        throw new IllegalArgumentException("Unknown requirement: " + id);
    }

    public synchronized TaskContextState snapshot() {
        return new TaskContextState(
                tasks,
                requirements,
                revision,
                scope,
                contractVersion,
                subjectVersion,
                verifications);
    }

    @JsonProperty("contractVersion")
    public synchronized long getContractVersion() {
        return contractVersion;
    }

    @JsonProperty("subjectVersion")
    public synchronized String getSubjectVersion() {
        return subjectVersion;
    }

    @JsonProperty("verifications")
    public synchronized List<TaskVerification> getVerifications() {
        return List.copyOf(verifications);
    }

    @JsonIgnore
    public synchronized EvidenceBinding getEvidenceBinding() {
        return scope == null || subjectVersion == null
                ? null
                : new EvidenceBinding(scope.taskId(), contractVersion, subjectVersion);
    }

    /** Caller must update this when the checked workspace/artifact changes, before execution. */
    public synchronized void setSubjectVersion(String version, long expectedRevision) {
        checkRevision(expectedRevision);
        TaskRequirement.requireText(version, "subject version", 2000);
        if (scope == null) throw new IllegalStateException("Task scope required for evidence");
        if (version.equals(subjectVersion)) return;
        subjectVersion = version;
        contractVersion++;
        revision++;
    }

    /** Invalidate immediately when a relevant mutation starts; repin only after it settles. */
    public synchronized void invalidateSubject(long expectedRevision) {
        checkRevision(expectedRevision);
        subjectVersion = null;
        contractVersion++;
        revision++;
    }

    /** Called after durable verification storage. Does not mark todos or the task completed. */
    public synchronized void recordVerification(TaskVerification report, long expectedRevision) {
        checkRevision(expectedRevision);
        Objects.requireNonNull(report, "report");
        if (!report.binding().equals(getEvidenceBinding())) {
            throw new ConcurrentModificationException("Verification subject or contract changed");
        }
        boolean confirmed =
                requirements.stream()
                        .anyMatch(
                                requirement ->
                                        requirement.id().equals(report.requirementId())
                                                && requirement.kind()
                                                        == TaskRequirement.Kind.ACCEPTANCE_CRITERION
                                                && requirement.status()
                                                        == TaskRequirement.Status.CONFIRMED);
        if (!confirmed)
            throw new IllegalArgumentException("Confirmed acceptance criterion required");
        verifications.removeIf(previous -> previous.requirementId().equals(report.requirementId()));
        verifications.add(report);
        revision++;
    }

    private void checkRevision(long expectedRevision) {
        if (revision != expectedRevision)
            throw new ConcurrentModificationException("Task revision changed");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskContextState other)) {
            return false;
        }
        return revision == other.revision
                && Objects.equals(tasks, other.tasks)
                && Objects.equals(requirements, other.requirements)
                && Objects.equals(scope, other.scope)
                && contractVersion == other.contractVersion
                && Objects.equals(subjectVersion, other.subjectVersion)
                && Objects.equals(verifications, other.verifications);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                tasks,
                requirements,
                revision,
                scope,
                contractVersion,
                subjectVersion,
                verifications);
    }

    @Override
    public String toString() {
        return "TaskContextState{tasks=" + tasks + '}';
    }
}
