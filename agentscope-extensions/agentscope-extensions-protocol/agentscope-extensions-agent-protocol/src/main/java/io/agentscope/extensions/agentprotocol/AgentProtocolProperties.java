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

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the AgentScope task HTTP protocol endpoints. */
@ConfigurationProperties(prefix = "agentscope.agent-protocol")
public class AgentProtocolProperties {

    /** When {@code true}, registers {@code /tasks} REST endpoints. */
    private boolean enabled = false;

    /** When {@code true}, servers push AgentEvents over SSE {@code /tasks/{id}/events}. */
    private boolean streamingEnabled = true;

    /** When {@code true}, pause remote tasks for tool confirmation (HITL). */
    private boolean hitlEnabled = true;

    /** Replay buffer size per task for late SSE subscribers. */
    private int sseReplayBufferSize = 256;

    /** Client idle timeout hint for SSE (ms); not enforced server-side by default. */
    private long sseTimeoutMs = 10_800_000L;

    /**
     * Dedicated control-plane directory for protocol task-record persistence. Independent of any
     * execution agent's workspace. When null/blank, defaults to {@code
     * ${user.dir}/.agentscope/agent-protocol}.
     */
    private String taskStorePath;

    /**
     * Lifetime of the execution lease a run holds in {@link ProtocolTaskRunRegistry}. When an
     * instance dies, its tasks become claimable again after this long. Must exceed {@link
     * #runHeartbeatIntervalMs} by a comfortable margin.
     */
    private long runLeaseTtlMs = 60_000L;

    /**
     * How often a running task renews its lease. Clamped to at most half of {@link #runLeaseTtlMs}
     * so that a clock skew or a slow registry cannot expire a live run.
     */
    private long runHeartbeatIntervalMs = 20_000L;

    /**
     * How often a running task checks whether cancellation has been requested. The intent travels
     * through the registry, so this is a poll interval: a process-local registry answers for free,
     * while a shared store costs one read per interval per running task. Raise it when the registry
     * is remote.
     */
    private long cancelCheckIntervalMs = 500L;

    /**
     * How long {@code /resume} waits for a paused run to finish handing its leases back.
     *
     * <p>The pause record becomes visible before the previous run releases its lease, so a client
     * reacting to it can arrive during that short window. Clamped to 100–30,000 ms; the default
     * keeps the previous fixed two-second bound. Raise it when a shared registry makes the handback
     * slower than that.
     */
    private long resumeLeaseWaitMs = 2_000L;

    /**
     * How long a task may stay paused for confirmation before the server ends the turn.
     *
     * <p>A paused turn occupies its session: a new submission for the same {@code session_id} is
     * rejected while it is pending, because the confirm decisions belong to that turn's state. This
     * bound is what releases the session when nobody answers — on expiry the task is settled as
     * failed with {@code confirm_timeout}, its event stream is closed, and the session becomes
     * submittable again.
     *
     * <p>Defaults to one day. <b>There is no upper bound:</b> the operator decides how long a pause
     * may last, so a deployment with slow approvals can raise this to a week or a month without the
     * framework truncating it. Only a one-second floor is applied, to keep a zero or negative value
     * from expiring the pause before the client can read it.
     */
    private long confirmTimeoutMs = 86_400_000L;

    /**
     * When {@code true}, a submitted task's record takes its {@code parentAgentId} / {@code
     * parentSessionId} from the caller-supplied {@code context.parent_agent_id} / {@code
     * context.parent_session_id}, so the record names the agent and conversation that issued it.
     * Absent or blank caller values still fall back to the protocol control-plane namespace.
     *
     * <p>When {@code false} (default), both fields always name that namespace ({@link
     * AgentProtocolConstants#PROTOCOL_AGENT_ID} / {@link
     * AgentProtocolConstants#PROTOCOL_SESSION_ID}), which is where {@link
     * WorkspaceProtocolTaskRepository} stores the record regardless of this setting. Enabling it is
     * therefore a reporting choice: the stored location does not move, and only the fields visible
     * on the record change. A caller-supplied value is never used for routing or session selection.
     */
    private boolean useCallerParentIdentity = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public void setStreamingEnabled(boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
    }

    public boolean isHitlEnabled() {
        return hitlEnabled;
    }

    public void setHitlEnabled(boolean hitlEnabled) {
        this.hitlEnabled = hitlEnabled;
    }

    public int getSseReplayBufferSize() {
        return sseReplayBufferSize;
    }

    public void setSseReplayBufferSize(int sseReplayBufferSize) {
        this.sseReplayBufferSize = sseReplayBufferSize;
    }

    public long getSseTimeoutMs() {
        return sseTimeoutMs;
    }

    public void setSseTimeoutMs(long sseTimeoutMs) {
        this.sseTimeoutMs = sseTimeoutMs;
    }

    /**
     * Resolved control-plane path. Never blank after this getter — falls back to {@code
     * ${user.dir}/.agentscope/agent-protocol}.
     */
    public String getTaskStorePath() {
        if (taskStorePath == null || taskStorePath.isBlank()) {
            return Path.of(System.getProperty("user.dir"), ".agentscope", "agent-protocol")
                    .toString();
        }
        return taskStorePath;
    }

    public void setTaskStorePath(String taskStorePath) {
        this.taskStorePath = taskStorePath;
    }

    public long getRunLeaseTtlMs() {
        return runLeaseTtlMs;
    }

    public void setRunLeaseTtlMs(long runLeaseTtlMs) {
        this.runLeaseTtlMs = runLeaseTtlMs;
    }

    public long getRunHeartbeatIntervalMs() {
        return runHeartbeatIntervalMs;
    }

    public void setRunHeartbeatIntervalMs(long runHeartbeatIntervalMs) {
        this.runHeartbeatIntervalMs = runHeartbeatIntervalMs;
    }

    public long getCancelCheckIntervalMs() {
        return cancelCheckIntervalMs;
    }

    public void setCancelCheckIntervalMs(long cancelCheckIntervalMs) {
        this.cancelCheckIntervalMs = cancelCheckIntervalMs;
    }

    public long getResumeLeaseWaitMs() {
        return resumeLeaseWaitMs;
    }

    public void setResumeLeaseWaitMs(long resumeLeaseWaitMs) {
        this.resumeLeaseWaitMs = resumeLeaseWaitMs;
    }

    public long getConfirmTimeoutMs() {
        return confirmTimeoutMs;
    }

    public void setConfirmTimeoutMs(long confirmTimeoutMs) {
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    public boolean isUseCallerParentIdentity() {
        return useCallerParentIdentity;
    }

    public void setUseCallerParentIdentity(boolean useCallerParentIdentity) {
        this.useCallerParentIdentity = useCallerParentIdentity;
    }
}
