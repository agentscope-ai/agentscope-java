/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.a2a.redis;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.server.hitl.A2aHitlDurabilityBinding;
import io.agentscope.core.a2a.server.hitl.HitlClaimRequest;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityVerification;
import io.agentscope.core.a2a.server.hitl.HitlExecutionKey;
import io.agentscope.core.a2a.server.hitl.HitlHandoffRecord;
import io.agentscope.core.a2a.server.hitl.HitlHandoffStatus;
import io.agentscope.core.a2a.server.hitl.HitlLeaseHandle;
import io.agentscope.core.a2a.server.hitl.HitlOpenRequest;
import io.agentscope.core.a2a.server.hitl.HitlResumeCoordinator;
import io.agentscope.core.a2a.server.hitl.HitlResumeRejectedException;
import io.agentscope.core.a2a.server.hitl.HitlSessionLease;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** Coherent Redis implementation of every durable A2A HITL control-plane component. */
public final class RedisA2aHitlDurabilityBinding implements A2aHitlDurabilityBinding {

    private static final Duration MAXIMUM_HANDOFF_PROBE_TTL = Duration.ofMinutes(5);

    private final RedissonClient redissonClient;
    private final String namespace;
    private final Duration taskTtl;
    private final RedisTaskStore taskStore;
    private final RedisHitlResumeCoordinator resumeCoordinator;
    private final RedisHitlSessionLease sessionLease;
    private final RedisHitlRecoveryReconciler recoveryReconciler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RedisA2aHitlDurabilityBinding(
            RedissonClient redissonClient, RedisHitlProperties properties) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        Objects.requireNonNull(properties, "properties");
        this.namespace = RedisTaskStore.normalizeNamespace(properties.getNamespace());
        this.taskTtl = RedisTaskStore.requirePositiveMillis(properties.getTaskTtl(), "taskTtl");
        this.taskStore = new RedisTaskStore(redissonClient, namespace, taskTtl);
        this.resumeCoordinator = new RedisHitlResumeCoordinator(redissonClient, namespace, taskTtl);
        this.sessionLease = new RedisHitlSessionLease(redissonClient, namespace);
        this.recoveryReconciler =
                new RedisHitlRecoveryReconciler(
                        resumeCoordinator,
                        properties.getClaimRecoveryTimeout(),
                        properties.getReconcilerInterval());
    }

    @Override
    public TaskStore taskStore() {
        return taskStore;
    }

    @Override
    public HitlResumeCoordinator resumeCoordinator() {
        return resumeCoordinator;
    }

    @Override
    public HitlSessionLease sessionLease() {
        return sessionLease;
    }

    @Override
    public HitlDurabilityVerification verify() {
        ensureOpen();
        verifyComponentTopology();
        verifyTaskRoundTrip();
        verifyHandoffAndLease();
        return new HitlDurabilityVerification("redis", namespace);
    }

    @Override
    public void start() {
        ensureOpen();
        if (started.compareAndSet(false, true)) {
            recoveryReconciler.start();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        recoveryReconciler.close();
        sessionLease.close();
    }

    private void verifyComponentTopology() {
        if (taskStore.redissonClient() != redissonClient
                || resumeCoordinator.redissonClient() != redissonClient
                || sessionLease.redissonClient() != redissonClient) {
            throw new IllegalStateException(
                    "Redis durable HITL components do not share the same RedissonClient");
        }
        if (!namespace.equals(taskStore.namespace())
                || !namespace.equals(resumeCoordinator.namespace())
                || !namespace.equals(sessionLease.namespace())) {
            throw new IllegalStateException(
                    "Redis durable HITL components do not share one namespace");
        }
    }

    private void verifyTaskRoundTrip() {
        String taskId = "__a2a_hitl_task_probe__" + UUID.randomUUID();
        Task task =
                Task.builder()
                        .id(taskId)
                        .contextId(taskId)
                        .status(new TaskStatus(TaskState.TASK_STATE_SUBMITTED))
                        .build();
        try {
            taskStore.save(task, false);
            Task restored = taskStore.get(taskId);
            if (restored == null || !taskId.equals(restored.id())) {
                throw new IllegalStateException("Redis TaskStore round-trip failed");
            }
            requireBoundedTtl(taskStore.taskKey(taskId), taskTtl, "TaskStore");
        } finally {
            taskStore.delete(taskId);
        }
        if (redissonClient.getKeys().countExists(taskStore.taskKey(taskId)) != 0) {
            throw new IllegalStateException("Redis TaskStore delete probe failed");
        }
    }

    private void verifyHandoffAndLease() {
        String suffix = UUID.randomUUID().toString();
        String taskId = "__a2a_hitl_handoff_task__" + suffix;
        String contextId = "__a2a_hitl_handoff_context__" + suffix;
        String token = "__a2a_hitl_secret_probe__" + UUID.randomUUID();
        HitlExecutionKey executionKey =
                new HitlExecutionKey("probe-user", "probe-agent", contextId);
        Duration handoffProbeTtl =
                taskTtl.compareTo(MAXIMUM_HANDOFF_PROBE_TTL) < 0
                        ? taskTtl
                        : MAXIMUM_HANDOFF_PROBE_TTL;
        HitlHandoffRecord record =
                resumeCoordinator.open(
                        new HitlOpenRequest(
                                taskId,
                                contextId,
                                executionKey,
                                A2aHandoffType.USER_CONFIRM,
                                List.of(
                                        new ToolUseBlock(
                                                "probe-tool-" + suffix,
                                                "probe",
                                                Map.of("value", 1))),
                                token,
                                handoffProbeTtl,
                                null));
        try {
            requireBoundedTtl(
                    resumeCoordinator.recordKey(record.handoffId()), taskTtl, "handoff record");
            Map<String, String> values =
                    redissonClient
                            .<String, String>getMap(
                                    resumeCoordinator.recordKey(record.handoffId()),
                                    StringCodec.INSTANCE)
                            .readAllMap();
            if (values.toString().contains(token) || token.equals(values.get("tokenDigest"))) {
                throw new IllegalStateException("Redis handoff stored a plaintext resume token");
            }
            HitlClaimRequest claim =
                    new HitlClaimRequest(taskId, contextId, record.handoffId(), token);
            resumeCoordinator.validateClaim(claim);
            HitlHandoffRecord claimed = resumeCoordinator.claim(claim);
            if (claimed.status() != HitlHandoffStatus.CLAIMED) {
                throw new IllegalStateException("Redis handoff claim probe failed");
            }
            try {
                resumeCoordinator.claim(claim);
                throw new IllegalStateException("Redis handoff replay probe failed");
            } catch (HitlResumeRejectedException expected) {
                // Expected: the token has already been consumed.
            }
            verifyLease(executionKey);
        } finally {
            resumeCoordinator.deleteVerificationRecord(record);
        }
    }

    private void verifyLease(HitlExecutionKey executionKey) {
        String key = sessionLease.leaseKey(executionKey);
        try (HitlLeaseHandle handle = sessionLease.acquire(executionKey, Duration.ofSeconds(10))) {
            requireBoundedTtl(key, Duration.ofSeconds(10), "session lease");
            if (!handle.isValid()) {
                throw new IllegalStateException("Redis session lease probe lost ownership");
            }
        }
        if (redissonClient.getKeys().countExists(key) != 0) {
            throw new IllegalStateException("Redis session lease release probe failed");
        }
    }

    private void requireBoundedTtl(String key, Duration maximum, String component) {
        long ttl = redissonClient.getBucket(key, StringCodec.INSTANCE).remainTimeToLive();
        if (ttl <= 0 || ttl > maximum.toMillis()) {
            throw new IllegalStateException(component + " Redis TTL probe failed");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Redis HITL binding is already closed");
        }
    }
}
