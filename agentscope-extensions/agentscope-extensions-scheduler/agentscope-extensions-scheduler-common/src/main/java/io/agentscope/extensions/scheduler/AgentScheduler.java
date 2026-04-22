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

import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.List;

/**
 * Core interface for scheduling agents to run at specified times or intervals.
 *
 * <p>This interface defines the fundamental operations for scheduling agents, including
 * registering tasks with various scheduling configurations (cron expressions, fixed rates,
 * fixed delays), managing task lifecycle (pause, resume, cancel), and querying scheduled agents.
 *
 * <p>Implementations can be based on different scheduling frameworks such as:
 * <ul>
 *   <li>Spring TaskScheduler - Lightweight implementation for simple scenarios</li>
 *   <li>Quartz Scheduler - Enterprise-grade implementation with persistence and clustering</li>
 *   <li>External schedulers - Integration with distributed scheduling systems (XXL-Job, etc.)</li>
 * </ul>
 *
 * <p><b>Design Principles:</b>
 * <ul>
 *   <li>Task-centric - Returns {@link ScheduleAgentTask} for task lifecycle management</li>
 *   <li>Framework agnostic - Core interface can be implemented with any scheduling backend</li>
 *   <li>Extensible - Support for custom scheduling strategies through configuration</li>
 *   <li>Stateful - Track task execution history, metrics, and current status</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * AgentScheduler scheduler = new XxlJobAgentScheduler(executor);
 *
 * // Create agent configuration
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .model(model)
 *     .toolkit(toolkit)
 *     .sysPrompt("You are a helpful assistant")
 *     .build();
 *
 * // Create schedule configuration
 * ScheduleConfig scheduleConfig = ScheduleConfig.builder()
 *     .cron("0 0 8 * * ?")
 *     .build();
 *
 * // Schedule the agent and get a task handle
 * ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
 *
 * // Control the task
 * task.run();      // Manual execution
 * task.cancel();   // Cancel scheduling
 *
 * // Or cancel through scheduler
 * scheduler.cancel(task.getName());
 * }</pre>
 *
 * @see ScheduleConfig
 * @see ScheduleAgentTask
 * @see AgentConfig
 */
public interface AgentScheduler {

    /**
     * Schedule an agent to run according to the specified configuration.
     *
     * <p>This method accepts an {@link AgentConfig} that defines how to create fresh Agent instances.
     * If you need to configure runtime objects like Toolkit and Hooks, pass a {@link io.agentscope.extensions.scheduler.config.RuntimeAgentConfig}
     * instance instead. Each time the scheduled task executes, a new Agent instance will be created,
     * providing better state isolation and resource management.
     *
     * <p>The returned {@link ScheduleAgentTask} provides task control methods (run, cancel) and
     * serves as a handle to manage the scheduled task. The agent name from {@link AgentConfig#getName()}
     * will be used as the identifier for retrieving the task later via {@link #getScheduledAgent(String)}.
     *
     * <p><b>Benefits:</b>
     * <ul>
     *   <li>Each execution gets a fresh Agent with clean state</li>
     *   <li>No long-lived Agent instances consuming resources</li>
     *   <li>Configuration can be serialized and persisted (when using AgentConfig)</li>
     *   <li>Task lifecycle is independent of Agent lifecycle</li>
     * </ul>
     *
     * @param agentConfig The agent configuration defining how to create agents (can be AgentConfig or RuntimeAgentConfig)
     * @param scheduleConfig The scheduling configuration including timing and execution policies
     * @return The ScheduleAgentTask handle for controlling the scheduled task
     * @throws IllegalArgumentException if agentConfig or scheduleConfig is null or invalid
     */
    ScheduleAgentTask schedule(AgentConfig agentConfig, ScheduleConfig scheduleConfig);

    /**
     * Cancel and remove a scheduled task permanently.
     *
     * <p>This operation permanently removes the task from the scheduler. If the task is
     * currently executing, the behavior depends on the scheduler implementation - it may
     * wait for completion or attempt to interrupt the execution.
     *
     * <p>This method provides centralized control through the scheduler. Alternatively, you can
     * call {@link ScheduleAgentTask#cancel()} directly on the task.
     *
     * @param name The name of the scheduled task to cancel
     * @return true if the task was successfully cancelled, false if not found or already removed
     */
    boolean cancel(String name);

    /**
     * Retrieve a scheduled task by its name.
     *
     * <p>The name is derived from {@link AgentConfig#getName()} when the task was scheduled.
     * This allows you to retrieve a task without keeping a reference to the
     * {@link ScheduleAgentTask} returned from {@link #schedule(AgentConfig, ScheduleConfig)}.
     *
     * <p>If multiple tasks with the same name are scheduled, the behavior depends on the
     * implementation - it may return the first one, the most recently scheduled, or throw an exception.
     *
     * @param name The name of the scheduled task
     * @return The ScheduleAgentTask if found, or null if no task with the given name is scheduled
     */
    ScheduleAgentTask getScheduledAgent(String name);

    /**
     * Retrieve all scheduled tasks managed by this scheduler.
     *
     * <p>This operation returns all scheduled tasks regardless of their status (scheduled, running,
     * paused, etc.). For large numbers of tasks, consider implementing pagination in
     * scheduler implementations.
     *
     * @return A list of all ScheduleAgentTask instances
     */
    List<ScheduleAgentTask> getAllScheduleAgentTasks();

    /**
     * Gracefully shutdown the scheduler.
     *
     * <p>This operation stops accepting new scheduling requests and waits for currently executing
     * agent tasks to complete. The maximum wait time depends on the implementation. After shutdown,
     * no methods on this scheduler should be called.
     *
     * <p>For implementations that support persistence (e.g., Quartz with database store),
     * task configurations may be preserved and can be recovered when the scheduler restarts.
     */
    void shutdown();

    /**
     * Get the type identifier of this scheduler implementation.
     *
     * <p>This can be used to distinguish between different scheduler implementations
     * (e.g., "spring", "quartz", "xxl-job") and for logging/monitoring purposes.
     *
     * @return The scheduler type identifier
     */
    String getSchedulerType();
}
