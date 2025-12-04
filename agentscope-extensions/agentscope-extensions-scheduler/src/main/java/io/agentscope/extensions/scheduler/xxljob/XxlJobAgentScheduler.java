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
package io.agentscope.extensions.scheduler.xxljob;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.scheduler.AgentConfig;
import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.BaseScheduleAgentTask;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.ScheduleConfig;
import io.agentscope.extensions.scheduler.ScheduleMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * AgentScheduler implementation based on XXL-Job distributed task scheduling platform.
 *
 * <p>This scheduler integrates with XXL-Job to provide distributed scheduling capabilities
 * for agents. Jobs are registered as JobHandlers in XXL-Job and can be managed through
 * the XXL-Job admin console.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Distributed scheduling across multiple executor instances</li>
 *   <li>Centralized management through XXL-Job admin console</li>
 *   <li>Support for cron expressions, fixed rate, and manual triggers</li>
 *   <li>Built-in monitoring and logging</li>
 *   <li>Fault tolerance and automatic failover</li>
 * </ul>
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>XXL-Job admin server must be running and accessible</li>
 *   <li>XxlJobExecutor must be properly initialized and started</li>
 *   <li>Jobs must be configured in XXL-Job admin console with matching JobHandler names</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // 1. Initialize XXL-Job executor (typically in Spring Boot configuration)
 * XxlJobExecutor executor = new XxlJobExecutor();
 * executor.setAdminAddresses("http://localhost:8080/xxl-job-admin");
 * executor.setAppname("agentscope-executor");
 * executor.setPort(9999);
 * executor.start();
 *
 * // 2. Create scheduler
 * AgentScheduler scheduler = new XxlJobAgentScheduler(executor);
 *
 * // 3. Create agent configuration
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .description("My scheduled agent")
 *     .agentFactory(() -> ReActAgent.builder()
 *         .name("MyAgent")
 *         .model(model)
 *         .toolkit(toolkit)
 *         .build())
 *     .build();
 *
 * // 4. Schedule the agent
 * ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();
 * ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
 *
 * // 5. Configure the job in XXL-Job admin console:
 * //    - JobHandler: MyAgent (same as agent name)
 * //    - Schedule type: CRON
 * //    - Cron expression: 0 0 8 * * ?
 *
 * // 6. The agent will be executed according to the schedule in XXL-Job
 * //    Each execution will create a fresh Agent instance
 * }</pre>
 *
 * <p><b>Important Notes:</b>
 * <ul>
 *   <li>The agent name from AgentConfig is used as the JobHandler name in XXL-Job</li>
 *   <li>Each execution creates a fresh Agent instance using the configured factory</li>
 *   <li>Schedule configuration (cron, fixedRate, fixedDelay) is managed in XXL-Job admin console</li>
 *   <li>This scheduler only registers the JobHandler; actual scheduling is controlled by XXL-Job</li>
 *   <li>Pause/resume operations are not directly supported; use XXL-Job admin console instead</li>
 * </ul>
 *
 * @author yaohui
 * @see AgentScheduler
 * @see ScheduleAgentTask
 */
public class XxlJobAgentScheduler implements AgentScheduler {

    private static final Logger logger = LoggerFactory.getLogger(XxlJobAgentScheduler.class);

    private final XxlJobExecutor executor;
    private final Map<String, ScheduleAgentTask> scheduleAgentTasks;

    /**
     * Constructor for XxlJobAgentScheduler.
     *
     * @param executor The XXL-Job executor instance (must be initialized and started)
     */
    public XxlJobAgentScheduler(XxlJobExecutor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("XxlJobExecutor must not be null");
        }
        this.executor = executor;
        this.scheduleAgentTasks = new ConcurrentHashMap<>();
    }

    /**
     * Schedule an agent with default schedule configuration.
     *
     * @param agentConfig The agent configuration
     * @return The scheduled task
     */
    public ScheduleAgentTask schedule(AgentConfig agentConfig) {
        return this.schedule(agentConfig, ScheduleConfig.builder().build());
    }

    @Override
    public ScheduleAgentTask schedule(AgentConfig agentConfig, ScheduleConfig scheduleConfig) {
        if (agentConfig == null) {
            throw new IllegalArgumentException("AgentConfig must not be null");
        }
        if (scheduleConfig == null) {
            throw new IllegalArgumentException("ScheduleConfig must not be null");
        }
        if (scheduleConfig.getScheduleMode() != null
                && scheduleConfig.getScheduleMode() != ScheduleMode.NONE) {
            throw new UnsupportedOperationException(
                    "XXL-Job scheduler implementation currently only supports scheduleType 'none'."
                            + " Other types are not supported yet.");
        }

        String jobName = agentConfig.getName();
        if (jobName == null || jobName.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent name must not be null or empty");
        }

        // Check if already scheduled
        if (scheduleAgentTasks.containsKey(jobName)) {
            logger.warn("Task '{}' is already scheduled, returning existing task", jobName);
            return scheduleAgentTasks.get(jobName);
        }

        // add xxl-job logging hook
        agentConfig
                .getHooks()
                .add(
                        new Hook() {
                            @Override
                            public <T extends HookEvent> Mono<T> onEvent(T event) {
                                if (event instanceof PostReasoningEvent postReasoningEvent) {
                                    Msg msg = postReasoningEvent.getReasoningMessage();
                                    XxlJobHelper.log(msg.getTextContent());
                                }
                                return Mono.just(event);
                            }
                        });

        // Create ScheduleAgent (implements ScheduleAgentTask)
        BaseScheduleAgentTask scheduleAgentTask =
                new BaseScheduleAgentTask(agentConfig, scheduleConfig, this);

        try {
            // Register JobHandler to XXL-Job
            XxlJobExecutor.registJobHandler(
                    jobName,
                    new IJobHandler() {
                        @Override
                        public void execute() throws Exception {
                            executeAgent(scheduleAgentTask);
                        }
                    });

            // Store in map
            scheduleAgentTasks.put(jobName, scheduleAgentTask);

            logger.info(
                    "Successfully registered task '{}' as XXL-Job handler. Please configure the"
                            + " job in XXL-Job admin console with JobHandler name: {}",
                    jobName,
                    jobName);

            return scheduleAgentTask;

        } catch (Exception e) {
            logger.error("Failed to register task '{}' as XXL-Job handler", jobName, e);
            throw new RuntimeException("Failed to schedule task: " + jobName, e);
        }
    }

    /**
     * Execute the scheduled task.
     * This method is called by XXL-Job when the job is triggered.
     * It creates a fresh Agent instance for each execution.
     *
     * @param scheduleAgentTask The scheduled task to execute
     */
    private void executeAgent(BaseScheduleAgentTask scheduleAgentTask) {
        String id = scheduleAgentTask.getId();
        String name = scheduleAgentTask.getName();
        XxlJobContext context = XxlJobContext.getXxlJobContext();

        try {
            logger.info(
                    "Executing scheduled task '{}' triggered by XXL-Job. JobId: {},"
                            + " name: {}, "
                            + " ExecutorParams: {}",
                    id,
                    name,
                    context != null ? context.getJobId() : "N/A",
                    context != null ? context.getJobParam() : "N/A");
            // Prepare input message with XXL-Job context
            String jobParam = context != null ? context.getJobParam() : "";
            Msg inputMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(jobParam).build())
                            .build();

            // Create a fresh Agent instance for this execution
            Msg result = scheduleAgentTask.run(inputMsg).block();

            // Increment execution count
            scheduleAgentTask.incrementExecutionCount();

            logger.info(
                    "Successfully executed scheduled task '{}'. Result: {}",
                    name,
                    result != null ? result.getTextContent() : "null");
            XxlJobHelper.handleSuccess("Successfully executed scheduled task '" + name + "'");
        } catch (Exception e) {
            logger.error("Failed to execute scheduled task '{}'", name, e);
            throw new RuntimeException("Task execution failed: " + name, e);
        }
    }

    @Override
    public boolean cancel(String name) {
        throw new UnsupportedOperationException(
                "Cancel operation is not supported by XxlJobAgentScheduler");
    }

    @Override
    public ScheduleAgentTask getScheduledAgent(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        return scheduleAgentTasks.get(name);
    }

    @Override
    public List<ScheduleAgentTask> getAllScheduleAgentTasks() {
        return new ArrayList<>(scheduleAgentTasks.values());
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down XxlJobAgentScheduler. Removing all registered JobHandlers...");
        this.executor.destroy();
        scheduleAgentTasks.clear();
        logger.info("XxlJobAgentScheduler shutdown completed");
    }

    @Override
    public String getSchedulerType() {
        return "xxl-job";
    }
}
