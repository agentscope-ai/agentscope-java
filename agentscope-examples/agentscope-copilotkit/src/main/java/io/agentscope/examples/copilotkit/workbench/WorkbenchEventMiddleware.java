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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.model.AguiContext;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Turns agent progress into shared-state frames while the run is still streaming.
 *
 * <p>Three things happen here, and each of them is what makes a panel in the browser move:
 *
 * <ul>
 *   <li><b>Seeding</b> — the {@code state} object and the {@code context} entries the client sent
 *       with the run are folded into the thread's {@link WorkbenchState}, so shared state is
 *       genuinely bidirectional instead of server-push only.
 *   <li><b>Task mirroring</b> — after every tool result the live
 *       {@code AgentState.getTasksContext()} task list (maintained by the built-in
 *       {@code todo_write} tool) is diffed into shared state, which is how the plan board updates
 *       mid-run rather than at the end.
 *   <li><b>Permission auditing</b> — {@link RequireUserConfirmEvent} and
 *       {@link UserConfirmResultEvent} are recorded so the audit panel shows the full ask → decide
 *       trail. The interrupt itself is emitted by the framework's own lifecycle converter.
 * </ul>
 *
 * <p>Queued {@link CustomEvent}s are spliced into the event stream around each upstream event.
 * Ordering matters: the opening snapshot goes out immediately <em>after</em>
 * {@link AgentStartEvent} so it lands behind {@code RUN_STARTED}, open steps are closed and
 * everything still queued is flushed <em>before</em> {@link AgentEndEvent} so every
 * {@code STEP_STARTED} has a matching {@code STEP_FINISHED} and nothing arrives after
 * {@code RUN_FINISHED}.
 *
 */
public class WorkbenchEventMiddleware implements MiddlewareBase {

    /**
     * {@code description} the browser uses to advertise its A2UI catalog through
     * {@code useAgentContext}.
     */
    public static final String CONTEXT_INLINE_CATALOG = "a2ui-inline-catalog";

    private static final Logger logger = LoggerFactory.getLogger(WorkbenchEventMiddleware.class);

    private final WorkbenchStateRegistry registry;

    public WorkbenchEventMiddleware(WorkbenchStateRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        WorkbenchState workbench = registry.forContext(ctx);
        seedFromClient(workbench, ctx);

        return next.apply(input)
                .concatMap(
                        event -> {
                            if (event instanceof AgentStartEvent) {
                                workbench.clearOpenSteps();
                                workbench.queueSnapshot();
                                return Flux.concat(Flux.just(event), drain(workbench));
                            }
                            observe(agent, ctx, workbench, event);
                            if (event instanceof AgentEndEvent) {
                                // AG-UI: every STEP_STARTED must be closed before RUN_FINISHED.
                                workbench.closeOpenSteps();
                                return Flux.concat(drain(workbench), Flux.just(event));
                            }
                            return Flux.concat(Flux.just(event), drain(workbench));
                        });
    }

    private static Flux<AgentEvent> drain(WorkbenchState workbench) {
        List<CustomEvent> queued = workbench.drainPendingEvents();
        return queued.isEmpty() ? Flux.empty() : Flux.fromIterable(queued).cast(AgentEvent.class);
    }

    private void observe(
            Agent agent, RuntimeContext ctx, WorkbenchState workbench, AgentEvent event) {
        if (event instanceof ToolResultEndEvent) {
            syncTasks(agent, ctx, workbench);
        }
    }

    /**
     * Mirrors the built-in task list into shared state; see {@link WorkbenchState#syncTasks}.
     *
     * <p>The per-session state lookup lives on {@link ReActAgent} rather than the {@link Agent}
     * interface, so agents without it simply skip the plan board.
     */
    private void syncTasks(Agent agent, RuntimeContext ctx, WorkbenchState workbench) {
        if (!(agent instanceof ReActAgent reActAgent)) {
            return;
        }
        try {
            TaskContextState tasks = reActAgent.getAgentState(ctx).getTasksContext();
            if (tasks != null) {
                workbench.syncTasks(tasks.getTasks());
            }
        } catch (Exception e) {
            logger.debug("跳过任务同步：{}", e.toString());
        }
    }

    /**
     * Folds the client-supplied {@code state} and {@code context} into the thread's workbench.
     *
     * <p>Both arrive on the {@link RunAgentInput} that the AG-UI adapter stashes in the
     * {@link RuntimeContext}.
     */
    private void seedFromClient(WorkbenchState workbench, RuntimeContext ctx) {
        RunAgentInput runInput = runInput(ctx);
        if (runInput == null) {
            return;
        }
        workbench.mergeFromClient(runInput.getState());

        for (AguiContext entry : runInput.getContext()) {
            if (entry == null || !CONTEXT_INLINE_CATALOG.equals(entry.getDescription())) {
                continue;
            }
            workbench.setClientComponents(parseClientComponents(entry.getValue()));
        }
    }

    private static RunAgentInput runInput(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(RunAgentInput.class);
    }

    /**
     * Reads {@code {"catalogId": "...", "components": ["MetricTile", ...]}} as reported by the
     * browser.
     *
     * @return the declared component names, or an empty set when the payload is absent or unusable
     */
    private static Set<String> parseClientComponents(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        try {
            Map<?, ?> payload = JsonUtils.getJsonCodec().fromJson(value, Map.class);
            if (!(payload.get("components") instanceof List<?> components)) {
                return Set.of();
            }
            Set<String> names = new LinkedHashSet<>();
            for (Object component : components) {
                if (component instanceof String name && !name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            logger.warn("浏览器上报的 A2UI 组件清单解析失败：{}", e.toString());
            return Set.of();
        }
    }

    private static String describe(ToolUseBlock toolCall) {
        if (toolCall == null) {
            return "";
        }
        Map<String, Object> args = toolCall.getInput();
        if (args == null || args.isEmpty()) {
            return toolCall.getName();
        }
        try {
            return JsonUtils.getJsonCodec().toJson(args);
        } catch (Exception e) {
            return String.valueOf(args);
        }
    }
}
