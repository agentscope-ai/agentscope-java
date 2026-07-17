/* Copyright 2024-2026 the original author or authors. */
package io.agentscope.core.a2a.server.hitl;

import java.util.Objects;
import java.util.UUID;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;

/** Reusable provider contract checks for custom durable HITL binding implementations. */
public final class A2aHitlDurabilityBindingContract {

    private A2aHitlDurabilityBindingContract() {}

    /**
     * Verifies generic binding invariants plus the provider's own topology proof.
     *
     * <p>Provider implementers can call this from their contract tests. The main starter also
     * executes it during durable-mode fail-fast validation.
     */
    public static HitlDurabilityVerification verify(A2aHitlDurabilityBinding binding) {
        Objects.requireNonNull(binding, "binding");
        HitlResumeCoordinator coordinator =
                Objects.requireNonNull(binding.resumeCoordinator(), "binding.resumeCoordinator()");
        if (coordinator.durabilityCapability() != HitlDurabilityCapability.DURABLE) {
            throw new IllegalStateException("binding resume coordinator must declare DURABLE");
        }
        HitlSessionLease lease =
                Objects.requireNonNull(binding.sessionLease(), "binding.sessionLease()");
        if (lease.durabilityCapability() != HitlDurabilityCapability.DURABLE) {
            throw new IllegalStateException("binding session lease must declare DURABLE");
        }
        verifyTaskStore(Objects.requireNonNull(binding.taskStore(), "binding.taskStore()"));
        return Objects.requireNonNull(binding.verify(), "binding.verify()");
    }

    private static void verifyTaskStore(TaskStore taskStore) {
        String id = "__a2a_hitl_durable_probe__" + UUID.randomUUID();
        Task probe =
                Task.builder()
                        .id(id)
                        .contextId(id)
                        .status(new TaskStatus(TaskState.TASK_STATE_SUBMITTED))
                        .build();
        try {
            taskStore.save(probe, false);
            Task restored = taskStore.get(id);
            if (restored == null || !id.equals(restored.id())) {
                throw new IllegalStateException("TaskStore write/read probe failed");
            }
        } finally {
            taskStore.delete(id);
        }
        if (taskStore.get(id) != null) {
            throw new IllegalStateException("TaskStore delete probe failed");
        }
    }
}
