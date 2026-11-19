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
package io.agentscope.extensions.agentprotocol;

import java.time.Duration;
import java.util.Optional;

/**
 * Execution-plane coordination for Agent Protocol tasks: who is running a task right now, and has
 * anyone asked for it to stop.
 *
 * <p>This is the shared-state counterpart of the in-process executor. It answers the three questions
 * the task store cannot answer from a single JVM:
 *
 * <ul>
 *   <li><strong>Ownership</strong> — {@link #tryAcquire} is a lease, so exactly one instance starts a
 *       run for a given {@code task_id}. This replaces an in-process "is a future still running?"
 *       check, which silently allowed a second instance to start the same task.
 *   <li><strong>Cancellation</strong> — {@link #requestCancel} records the intent for the whole
 *       cluster and delivers it to the owner, which stops cooperatively. A local-only interrupt
 *       cancels nothing when the run lives on another instance.
 *   <li><strong>Liveness</strong> — {@link #find} exposes the current lease, so a caller can tell a
 *       live run from one whose owner died. Whether an expired lease is retried, failed, or left
 *       alone is an application decision; this interface only reports the facts.
 * </ul>
 *
 * <p>The shipped implementation is {@link BaseStoreProtocolTaskRunRegistry}, which stores leases and
 * cancel intents in a {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore}; its
 * {@code inMemory()} factory is the process-local default and keeps the single-instance behaviour.
 * A multi-instance deployment points that implementation at a shared store, or supplies its own as
 * the {@code ProtocolTaskRunRegistry} bean — for example Redis {@code SET task:{id}
 * <ownerId:leaseId> NX PX <ttl>} for the lease and pub/sub for cancel delivery. Such an
 * implementation must:
 *
 * <ul>
 *   <li>make {@link #tryAcquire} atomic across instances;
 *   <li>keep {@link #bindCancelHandler} process-local and use it to deliver a {@link #requestCancel}
 *       that arrives on a different instance;
 *   <li>clear any cancel intent when the owning lease is released.
 * </ul>
 */
public interface ProtocolTaskRunRegistry {

    /**
     * Claims execution ownership of {@code taskId} for {@code leaseTtl}, or returns empty when a
     * live lease is already held — by this or any other instance.
     *
     * <p>The caller is expected to release the lease when the run finishes, and to pause-hold it
     * across an {@code awaiting_confirm} window only by releasing it: a paused task has no owner, so
     * that any instance can serve the following {@code /resume}.
     */
    Optional<ProtocolTaskLease> tryAcquire(String taskId, Duration leaseTtl);

    /**
     * Extends the lease to {@code now + leaseTtl}; {@code false} when it is no longer held.
     *
     * <p>{@code false} is not advisory. It means ownership has moved — the lease expired and another
     * instance may already be running the task — so the caller must stop its own run instead of
     * finishing it as a second owner. Implementations must therefore keep answering {@code false}
     * for a lease they no longer hold rather than extending it best-effort.
     */
    boolean renew(ProtocolTaskLease lease, Duration leaseTtl);

    /** Gives up the lease. Idempotent: a lease already expired or taken over stays untouched. */
    void release(ProtocolTaskLease lease);

    /** Returns the live lease for {@code taskId}, or empty when nobody owns it. */
    Optional<ProtocolTaskLease> find(String taskId);

    /**
     * Registers the handler that stops the run of {@code taskId} on <em>this</em> instance. It is
     * invoked by {@link #requestCancel} when the intent reaches the owner.
     */
    void bindCancelHandler(String taskId, Runnable handler);

    /** Removes the handler registered by {@link #bindCancelHandler}. Idempotent. */
    void unbindCancelHandler(String taskId);

    /**
     * Records a cancellation intent for the cluster and delivers it to the owning instance.
     *
     * @return {@code true} when a live run was found and signalled, {@code false} when there was
     *     nothing to cancel
     */
    boolean requestCancel(String taskId);

    /** Whether cancellation has been requested for {@code taskId}; polled by the run itself. */
    boolean isCancelled(String taskId);
}
