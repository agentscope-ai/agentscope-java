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
package io.agentscope.extensions.scheduler;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

/**
 * Default implementation of {@link ScheduleAgentTask}.
 *
 * <p>This class represents a scheduled agent task that manages the lifecycle and execution
 * of agents according to a schedule. It uses {@link AgentConfig} to dynamically create
 * fresh Agent instances for each execution, providing better state isolation and resource management.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li><b>Dynamic Agent Creation</b> - Creates fresh Agent instances for each execution</li>
 *   <li><b>State Isolation</b> - Each execution gets a clean Agent state</li>
 *   <li><b>Task Control</b> - Provides methods to run and cancel the scheduled task</li>
 *   <li><b>Status Monitoring</b> - Access to execution metrics and scheduling information</li>
 *   <li><b>Thread Safety</b> - Safe for concurrent access from multiple threads</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create agent configuration
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .build();
 *
 * // Schedule the agent
 * ScheduleConfig scheduleConfig = ScheduleConfig.builder()
 *     .cron("0 0 8 * * ?")
 *     .build();
 * ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
 *
 * // Control the task
 * String taskId = task.getId();
 * String taskName = task.getName();
 *
 * // Manually trigger execution
 * task.run();
 *
 * // Cancel the task
 * task.cancel();
 *
 * // Query execution info (if cast to BaseScheduleAgentTask)
 * if (task instanceof BaseScheduleAgentTask baseTask) {
 *     boolean cancelled = baseTask.isCancelled();
 *     long count = baseTask.getExecutionCount();
 * }
 * }</pre>
 *
 * <p><b>Thread Safety:</b>
 * This class is designed to be thread-safe as scheduling operations may be triggered from
 * different threads. Each execution creates a new Agent instance, ensuring thread-safe execution.
 *
 * @see ScheduleAgentTask
 * @see AgentScheduler
 * @see AgentConfig
 * @see ScheduleConfig
 */
public class BaseScheduleAgentTask implements ScheduleAgentTask<Msg> {

    private final String id;
    private final RuntimeAgentConfig agentConfig;
    private final ScheduleConfig scheduleConfig;
    private final AgentScheduler scheduler;
    private final AtomicBoolean cancelled;
    private final AtomicLong executionCount;

    /**
     * Constructor for BaseScheduleAgentTask.
     *
     * @param agentConfig The runtime agent configuration containing model, toolkit, and hooks
     * @param scheduleConfig The schedule configuration defining timing and execution policies
     * @param scheduler The scheduler managing this task
     */
    public BaseScheduleAgentTask(
            RuntimeAgentConfig agentConfig,
            ScheduleConfig scheduleConfig,
            AgentScheduler scheduler) {
        this.id = UUID.randomUUID().toString();
        this.agentConfig = agentConfig;
        this.scheduleConfig = scheduleConfig;
        this.scheduler = scheduler;
        this.cancelled = new AtomicBoolean(false);
        this.executionCount = new AtomicLong(0);
    }

    // ==================== ScheduleAgentTask Interface Implementation ====================

    /**
     * Get the unique identifier of this scheduled task.
     *
     * <p>The ID is automatically generated as a UUID when the task is created.
     *
     * @return The unique task identifier
     */
    @Override
    public String getId() {
        return id;
    }

    /**
     * Get the name of this scheduled task.
     *
     * <p>The name is derived from the agent configuration.
     *
     * @return The task name
     */
    @Override
    public String getName() {
        return agentConfig.getName();
    }

    /**
     * Execute the scheduled task immediately.
     *
     * <p>This method creates a fresh Agent instance and executes it with the provided input messages.
     * Each execution increments the execution count for tracking purposes.
     *
     * @param msgs The input messages to pass to the agent (optional, can be empty or null)
     * @return A Mono that emits the agent's response message when execution completes
     * @throws IllegalStateException if the task has been cancelled
     */
    @Override
    public Mono<Msg> run(Msg... msgs) {
        if (cancelled.get()) {
            throw new IllegalStateException("Cannot run a cancelled task: " + getName());
        }
        // Create a fresh Agent instance for this execution
        Agent agent = createAgent();

        Mono<Msg> result =
                agent.call(msgs == null || msgs.length == 0 ? List.of() : Arrays.asList(msgs));
        // Increment execution count for tracking
        incrementExecutionCount();
        return result;
    }

    /**
     * Cancel this scheduled task permanently.
     *
     * <p>This method marks the task as cancelled and delegates to the scheduler
     * to remove the task from its schedule. After cancellation, calling {@link #run(Msg...)}
     * will throw an IllegalStateException.
     */
    @Override
    public void cancel() {
        cancelled.set(true);
        scheduler.cancel(getName());
    }

    // ==================== Additional Methods for Task Management ====================

    /**
     * Check if this task has been cancelled.
     *
     * <p>A cancelled task will not be executed by the scheduler and cannot be manually run.
     *
     * @return true if the task is cancelled, false otherwise
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
    public ScheduleConfig getScheduleConfig() {
        return scheduleConfig;
    }

    /**
     * Get the runtime agent configuration.
     *
     * <p>Returns the {@link RuntimeAgentConfig} containing all configuration including
     * runtime objects (Model, Toolkit, Hooks) used to create agent instances for this task.
     *
     * @return The RuntimeAgentConfig instance
     */
    public RuntimeAgentConfig getAgentConfig() {
        return agentConfig;
    }

    /**
     * Create and return a fresh Agent instance.
     *
     * <p>This method is intended for internal use by the scheduler. It creates a new ReActAgent
     * instance with clean state for each execution, using the configuration from RuntimeAgentConfig.
     * The agent is built with the configured model, toolkit (if provided), and hooks (if provided).
     *
     * @return A new ReActAgent instance
     * @throws IllegalArgumentException if name, sysPrompt, or model is missing
     */
    private Agent createAgent() {
        String name = Optional.ofNullable(this.agentConfig.getName()).orElse("");
        String sysPrompt = Optional.ofNullable(this.agentConfig.getSysPrompt()).orElse("");

        if (name.isEmpty() || sysPrompt.isEmpty()) {
            throw new IllegalArgumentException(
                    "Parameter configuration error: name or sysPrompt cannot be empty.");
        }
        ReActAgent.Builder builder = ReActAgent.builder().name(name).sysPrompt(sysPrompt);
        Model model = this.agentConfig.getModel();
        if (model != null) {
            builder.model(this.agentConfig.getModel());
        } else {
            throw new IllegalArgumentException("Model cannot be null.");
        }
        Toolkit toolkit = this.agentConfig.getToolkit();
        if (toolkit != null) {
            builder.toolkit(toolkit);
        }
        List<Hook> hooks = this.agentConfig.getHooks();
        if (hooks != null) {
            builder.hooks(hooks);
        }

        return builder.build();
    }

    /**
     * Get the total number of times this task has been executed.
     *
     * <p>This count includes both scheduled executions and manual executions via {@link #run(Msg...)}.
     * It tracks successful executions; if execution fails with an exception, the count is still incremented.
     *
     * @return The total execution count (starts at 0)
     */
    public long getExecutionCount() {
        return executionCount.get();
    }

    /**
     * Increment the execution count.
     *
     * <p>This method is called internally after each execution and is intended for
     * use by the scheduler implementation or subclasses.
     */
    public void incrementExecutionCount() {
        executionCount.incrementAndGet();
    }
}
