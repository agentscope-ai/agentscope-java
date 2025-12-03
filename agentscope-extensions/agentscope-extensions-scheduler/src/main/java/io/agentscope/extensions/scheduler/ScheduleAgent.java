/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.scheduler;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A scheduled agent that combines the capabilities of an {@link Agent} with scheduling control.
 *
 * <p>This class implements the {@link Agent} interface and follows the Decorator pattern,
 * wrapping an existing Agent instance with additional scheduling functionality without
 * modifying the original agent's behavior.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li><b>Agent Delegation</b> - All standard Agent methods are delegated to the underlying agent</li>
 *   <li><b>Schedule Control</b> - Provides methods to pause, resume, and cancel scheduled execution</li>
 *   <li><b>Status Monitoring</b> - Access to scheduling status, execution metrics, and configuration</li>
 *   <li><b>Lifecycle Management</b> - Integrated lifecycle between agent execution and scheduling</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Schedule an agent
 * Agent myAgent = new ReActAgent(...);
 * ScheduleConfig config = ScheduleConfig.builder()
 *     .cron("0 0 8 * * ?")
 *     .build();
 * ScheduleAgent scheduledAgent = scheduler.schedule(myAgent, config);
 *
 * // Use as a normal agent
 * Msg response = scheduledAgent.call(Msg.system("Hello")).block();
 *
 * // Control scheduling
 * scheduledAgent.cancel();  // Cancel and remove from scheduler
 *
 * // Query execution info
 * boolean cancelled = scheduledAgent.isCancelled();
 * Instant nextRun = scheduledAgent.getNextExecutionTime();
 * long count = scheduledAgent.getExecutionCount();
 * }</pre>
 *
 * <p><b>Thread Safety:</b>
 * This class is designed to be thread-safe as scheduling operations may be triggered from
 * different threads. The underlying delegate agent's thread safety depends on its implementation.
 *
 * @author yaohui
 * @see AgentScheduler
 * @see Agent
 * @see ScheduleConfig
 */
public class ScheduleAgent implements Agent {

    private final Agent delegate;
    private final ScheduleConfig config;
    private final AgentScheduler scheduler;
    private final AtomicBoolean cancelled;
    private final AtomicReference<Instant> nextExecutionTime;
    private final AtomicLong executionCount;

    /**
     * Constructor for ScheduleAgent.
     *
     * @param delegate The underlying agent to be scheduled
     * @param config The schedule configuration
     * @param scheduler The scheduler managing this agent
     */
    public ScheduleAgent(Agent delegate, ScheduleConfig config, AgentScheduler scheduler) {
        this.delegate = delegate;
        this.config = config;
        this.scheduler = scheduler;
        this.cancelled = new AtomicBoolean(false);
        this.nextExecutionTime = new AtomicReference<>();
        this.executionCount = new AtomicLong(0);
    }

    // ==================== Agent Interface Implementation (Delegated) ====================

    @Override
    public String getAgentId() {
        return delegate.getAgentId();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return delegate.call(msgs);
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
        return delegate.call(msgs, structuredModel);
    }

    @Override
    public Mono<Void> observe(Msg msg) {
        return delegate.observe(msg);
    }

    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        return delegate.observe(msgs);
    }

    @Override
    public void interrupt() {
        delegate.interrupt();
    }

    @Override
    public void interrupt(Msg msg) {
        delegate.interrupt(msg);
    }

    @Override
    public Flux<Event> stream(Msg msg, StreamOptions options) {
        return delegate.stream(msg, options);
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return delegate.stream(msgs, options);
    }

    // ==================== Scheduling Control Methods ====================

    /**
     * Cancel the scheduled execution of this agent permanently.
     *
     * <p>This operation removes the agent from the scheduler. After cancellation:
     * <ul>
     *   <li>No further scheduled executions will occur</li>
     *   <li>The agent can still be used as a regular Agent (manual calls)</li>
     *   <li>Calling cancel() again may have no effect</li>
     *   <li>The scheduler will no longer track this agent</li>
     * </ul>
     *
     * <p>If the agent is currently executing, the behavior depends on the scheduler implementation:
     * <ul>
     *   <li>Some implementations may wait for the current execution to complete</li>
     *   <li>Others may attempt to interrupt the execution</li>
     * </ul>
     *
     * <p><b>Note:</b> This operation is typically irreversible.
     */
    public void cancel() {
        cancelled.set(true);
        scheduler.cancel(getName());
    }

    /**
     * Check if this agent is cancelled.
     *
     * @return true if the agent is cancelled
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Get the schedule configuration used for this agent.
     *
     * <p>Returns the configuration that was provided when this agent was scheduled,
     * including timing settings, execution policies, and other scheduling parameters.
     *
     * @return The ScheduleConfig for this scheduled agent
     */
    public ScheduleConfig getConfig() {
        return config;
    }

    /**
     * Get the underlying agent that is being scheduled.
     *
     * <p>This returns the original Agent instance that was passed to
     * {@link AgentScheduler#schedule(Agent, ScheduleConfig)}. This is useful when you need
     * to access agent-specific methods or properties that are not part of the standard Agent interface.
     *
     * @return The delegate Agent instance
     */
    public Agent getDelegate() {
        return delegate;
    }

    /**
     * Get the next scheduled execution time for this agent.
     *
     * <p>Returns the timestamp when this agent is scheduled to execute next. If the agent
     * is paused or cancelled, this may return null depending on the implementation.
     *
     * <p>For cron-based schedules, this will be calculated based on the cron expression.
     * For fixed-rate or fixed-delay schedules, this will be calculated based on the last
     * execution time and the configured interval.
     *
     * @return The next execution time, or null if not scheduled
     */
    public Instant getNextExecutionTime() {
        return nextExecutionTime.get();
    }

    /**
     * Set the next scheduled execution time.
     * This method is intended for use by the scheduler implementation.
     *
     * @param time The next execution time
     */
    public void setNextExecutionTime(Instant time) {
        nextExecutionTime.set(time);
    }

    /**
     * Get the total number of times this agent has been executed by the scheduler.
     *
     * <p>This count includes both successful and failed executions. It does not include
     * manual executions via {@link #call(Msg)} or other Agent methods.
     *
     * @return The total execution count
     */
    public long getExecutionCount() {
        return executionCount.get();
    }

    /**
     * Increment the execution count.
     * This method is intended for use by the scheduler implementation.
     */
    public void incrementExecutionCount() {
        executionCount.incrementAndGet();
    }
}
