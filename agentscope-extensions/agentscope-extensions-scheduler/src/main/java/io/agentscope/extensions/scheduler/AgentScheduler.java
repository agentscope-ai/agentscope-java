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
 *   <li>Agent-centric - Returns {@link ScheduleAgent} that combines agent capabilities with scheduling control</li>
 *   <li>Reactive API - All operations return Reactor's Mono/Flux for async execution</li>
 *   <li>Framework agnostic - Core interface can be implemented with any scheduling backend</li>
 *   <li>Extensible - Support for custom scheduling strategies through configuration</li>
 *   <li>Stateful - Track task execution history, metrics, and current status</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * AgentScheduler scheduler = new SpringTaskScheduler();
 * ScheduleConfig config = ScheduleConfig.cron("0 0 8 * * ?").build();
 *
 * // Schedule an agent and get a ScheduleAgent wrapper
 * ScheduleAgent scheduledAgent = scheduler.schedule(myAgent, config);
 *
 * // Control scheduling directly on the ScheduleAgent
 * scheduledAgent.pause();
 * scheduledAgent.resume();
 *
 * // Or control through the scheduler
 * scheduler.pause(scheduledAgent);
 * scheduler.cancel(scheduledAgent);
 * }</pre>
 *
 * @see ScheduleConfig
 * @see ScheduleAgent
 */
public interface AgentScheduler {

    /**
     * Schedule an agent to run according to the specified configuration.
     *
     * <p>The agent will be wrapped in a {@link ScheduleAgent} that provides both the original
     * agent's capabilities and scheduling control methods. The agent will be executed based on
     * the scheduling strategy defined in the config, such as cron expression, fixed rate, or
     * fixed delay.
     *
     * <p>The agent name from {@link Agent#getName()} will be used as the identifier for
     * retrieving the scheduled agent later via {@link #getScheduledAgent(String)}.
     *
     * @param agent The agent to be scheduled
     * @param config The scheduling configuration including timing, execution policies, etc.
     * @return The ScheduleAgent wrapper that combines agent and scheduling capabilities
     * @throws IllegalArgumentException if agent or config is null or invalid
     */
    ScheduleAgent schedule(Agent agent, ScheduleConfig config);

    /**
     * Cancel and remove a scheduled agent permanently.
     *
     * <p>This operation permanently removes the agent from the scheduler. If the agent is
     * currently running, the behavior depends on the scheduler implementation - it may
     * wait for completion or attempt to interrupt the execution.
     *
     * <p>This method provides centralized control through the scheduler. Alternatively, you can
     * call {@link ScheduleAgent#cancel()} directly on the scheduled agent.
     *
     * @param name The scheduled agent to cancel
     * @return true if the agent was successfully cancelled, false if the agent was not found or already removed
     */
    boolean cancel(String name);

    /**
     * Retrieve a scheduled agent by its name.
     *
     * <p>The name is derived from the original agent's {@link Agent#getName()} method when
     * the agent was scheduled. This allows you to retrieve a scheduled agent instance without
     * keeping a reference to the {@link ScheduleAgent} returned from {@link #schedule(Agent, ScheduleConfig)}.
     *
     * <p>If multiple agents with the same name are scheduled, the behavior depends on the
     * implementation - it may return the first one, the most recently scheduled, or throw an exception.
     *
     * @param name The name of the scheduled agent
     * @return The ScheduleAgent if found, or null if no agent with the given name is scheduled
     */
    ScheduleAgent getScheduledAgent(String name);

    /**
     * Retrieve all scheduled agents managed by this scheduler.
     *
     * <p>This operation returns all scheduled agents regardless of their status (scheduled, running,
     * paused, etc.). For large numbers of agents, consider implementing pagination in
     * scheduler implementations.
     *
     * @return A list of all ScheduleAgent instances
     */
    List<ScheduleAgent> getAllScheduledAgents();

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
