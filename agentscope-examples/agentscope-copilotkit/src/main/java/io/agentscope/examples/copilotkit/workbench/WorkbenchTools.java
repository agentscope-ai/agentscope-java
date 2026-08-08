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
package io.agentscope.examples.copilotkit.workbench;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The workbench tool belt.
 *
 * <p>Each tool exercises a different AgentScope 2.0 capability and is wired to a matching permission
 * rule in {@code AgentConfiguration}:
 *
 * <ul>
 *   <li>{@code query_service_metrics} — read-only, auto-allowed
 *   <li>{@code update_workbench_brief} — writes shared state, streamed to the browser as STATE_DELTA
 *   <li>{@code deploy_release} — ASK rule, suspends the run into an AG-UI interrupt
 *   <li>{@code purge_production_data} — DENY rule, never reaches this class
 *   <li>{@code render_dashboard} — generative UI through A2UI
 * </ul>
 *
 * <p>{@code AgentState} is injected by the framework and carries the AG-UI thread id as its session
 * id, which is how each tool finds the right {@link WorkbenchState}.
 *
 */
public class WorkbenchTools {

    private final WorkbenchStateRegistry registry;

    public WorkbenchTools(WorkbenchStateRegistry registry) {
        this.registry = registry;
    }

    /**
     * Reads simulated service metrics.
     *
     * @param service the service to inspect
     * @param window  the time window, e.g. {@code 1h} or {@code 24h}
     * @param state   injected agent state
     * @return a human-readable metric summary
     */
    @Tool(
            name = "query_service_metrics",
            description = "查询指定服务的运行指标（QPS、错误率、P99 延迟）。只读操作，可直接调用。",
            readOnly = true,
            stateInjected = true)
    public String queryServiceMetrics(
            @ToolParam(name = "service", description = "服务名，例如 order-api") String service,
            @ToolParam(name = "window", description = "时间窗口，例如 1h / 24h") String window,
            AgentState state) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("QPS", random.nextInt(800, 4200));
        metrics.put("错误率", "%.2f%%".formatted(random.nextDouble(0.01, 1.8)));
        metrics.put("P99 延迟", random.nextInt(80, 520) + "ms");
        metrics.put("实例数", random.nextInt(3, 24));

        WorkbenchState workbench = registry.forState(state);
        workbench.recordMetrics(metrics);
        return "%s 在 %s 窗口的指标：%s".formatted(service, window, metrics);
    }

    /**
     * Updates the shared brief shown in the browser.
     *
     * @param topic    new topic, optional
     * @param priority new priority (低 / 中 / 高), optional
     * @param status   new status text, optional
     * @param state    injected agent state
     * @return confirmation of the merged brief
     */
    @Tool(
            name = "update_workbench_brief",
            description = "更新工作台共享状态中的主题、优先级或状态文案，前端会实时收到 STATE_DELTA。",
            stateInjected = true,
            concurrencySafe = false)
    public String updateWorkbenchBrief(
            @ToolParam(name = "topic", description = "新的演示主题，可留空") String topic,
            @ToolParam(name = "priority", description = "优先级：低 / 中 / 高，可留空") String priority,
            @ToolParam(name = "status", description = "新的状态文案，可留空") String status,
            AgentState state) {
        return registry.forState(state).updateBrief(topic, priority, status);
    }

    /**
     * Records the goal the current plan is working towards.
     *
     * @param goal  the plan goal
     * @param state injected agent state
     * @return confirmation
     */
    @Tool(
            name = "set_plan_goal",
            description = "在使用 todo_write 制定计划前，先声明本次计划的总目标，前端计划面板会展示它。",
            stateInjected = true,
            concurrencySafe = false)
    public String setPlanGoal(
            @ToolParam(name = "goal", description = "计划的总目标") String goal, AgentState state) {
        registry.forState(state).setPlanGoal(goal);
        return "计划目标已记录：" + goal;
    }

    /**
     * Simulates a production release; gated by an {@code ASK} permission rule.
     *
     * @param service target service
     * @param version release version
     * @param state   injected agent state
     * @return the release result
     */
    @Tool(
            name = "deploy_release",
            description = "把指定版本发布到生产环境。高风险操作，执行前需要用户在前端确认。",
            stateInjected = true,
            concurrencySafe = false)
    public String deployRelease(
            @ToolParam(name = "service", description = "服务名") String service,
            @ToolParam(name = "version", description = "版本号，例如 v2.4.1") String version,
            AgentState state) {
        String detail = "%s → %s".formatted(service, version);
        return "已发布 %s 到生产环境，灰度 10%% 观察中。".formatted(detail);
    }

    /**
     * Destructive operation kept behind a {@code DENY} rule so the demo can show a hard block.
     *
     * @param dataset dataset name
     * @return never returned in practice
     */
    @Tool(
            name = "purge_production_data",
            description = "永久删除生产数据集。该工具被 DENY 规则拦截，任何情况下都不会执行。",
            concurrencySafe = false)
    public String purgeProductionData(
            @ToolParam(name = "dataset", description = "数据集名称") String dataset) {
        return "不应该执行到这里：" + dataset;
    }
}
