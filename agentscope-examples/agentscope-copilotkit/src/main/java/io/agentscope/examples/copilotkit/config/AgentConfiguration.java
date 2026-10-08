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
package io.agentscope.examples.copilotkit.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvents;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.examples.copilotkit.a2ui.A2uiComposer;
import io.agentscope.examples.copilotkit.service.DemoThreadStore;
import io.agentscope.examples.copilotkit.service.InMemoryAgentEventStore;
import io.agentscope.examples.copilotkit.service.PersistingAgentEventEnricher;
import io.agentscope.examples.copilotkit.workbench.RefundOrderTool;
import io.agentscope.examples.copilotkit.workbench.WorkbenchAguiEventConverter;
import io.agentscope.examples.copilotkit.workbench.WorkbenchEventMiddleware;
import io.agentscope.examples.copilotkit.workbench.WorkbenchStateRegistry;
import io.agentscope.examples.copilotkit.workbench.WorkbenchTools;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import reactor.core.publisher.Flux;

/**
 * Configuration class that registers agents with the AG-UI registry.
 *
 * <p>This example demonstrates how to register multiple agents with different IDs.
 * Clients can select which agent to use via:
 * <ul>
 *   <li>URL path variable: {@code POST /agui/run/{agentId}}</li>
 *   <li>HTTP header: {@code X-Agent-Id: agentId}</li>
 *   <li>Request body: {@code forwardedProps.agentId}</li>
 * </ul>
 */
@Configuration
public class AgentConfiguration {

    private final WorkbenchStateRegistry workbenchStateRegistry;
    private final A2uiComposer a2uiComposer;

    public AgentConfiguration(
            WorkbenchStateRegistry workbenchStateRegistry, A2uiComposer a2uiComposer) {
        this.workbenchStateRegistry = workbenchStateRegistry;
        this.a2uiComposer = a2uiComposer;
    }

    @Bean
    public AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer() {
        AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer =
                registry -> {
                    // The showcase agent: permission gating, plan tasks, shared state and A2UI.
                    registry.registerFactory("workbench", this::createWorkbenchAgent);

                    // Register a factory for the default agent
                    // Using a factory ensures each request gets a fresh agent instance
                    registry.registerFactory("default", this::createDefaultAgent);

                    // Register additional agents with different IDs
                    // Example: a simple chat agent without tools
                    registry.registerFactory("chat", this::createChatAgent);

                    // Example: an agent specialized for calculations
                    registry.registerFactory("calculator", this::createCalculatorAgent);
                };

        System.out.println(
                "Registered agents with AG-UI registry: workbench, default, chat, calculator");
        System.out.println("Access agents via:");
        System.out.println("  - POST /agui/run (uses default-agent-id from config)");
        System.out.println("  - POST /agui/run/chat (uses 'chat' agent)");
        System.out.println(
                "  - POST /agui/run/agent/{agentId}/run (CopilotKit multi-route, see"
                        + " CopilotKitRouteConfiguration)");
        System.out.println("  - POST /agui/run with X-Agent-Id header");

        return aguiAgentRegistryCustomizer;
    }

    @Bean
    public AguiEventEnricher exampleAguiEventEnricher() {
        return (source, events, context) -> {
            long timestamp = System.currentTimeMillis();
            return events.stream()
                    .map(event -> enrichExampleEvent(source, event, timestamp))
                    .toList();
        };
    }

    /**
     * Persist each source {@link AgentEvent} so {@code /agent/{agentId}/connect} can re-project
     * AG-UI frames through converters.
     *
     * <p>Ordered last among enrichers; it only records the AgentEvent and does not mutate frames.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public PersistingAgentEventEnricher persistingAgentEventEnricher(
            InMemoryAgentEventStore eventStore,
            ObjectProvider<WorkbenchAguiEventConverter> workbenchConverterProvider) {
        return new PersistingAgentEventEnricher(eventStore, workbenchConverterProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public AguiRuntimeContextResolver aguiRuntimeContextResolver() {
        return request -> {
            // Demo identity: treat X-Token as the user id when present.
            String token = request.firstHeader("X-Token");
            String userId = token == null || token.isBlank() ? DemoThreadStore.DEMO_USER_ID : token;
            return RuntimeContext.builder()
                    .sessionId(request.getInput().getThreadId())
                    .userId(userId)
                    .build();
        };
    }

    /**
     * The showcase agent, wired so that one conversation can exercise every capability the demo
     * claims.
     *
     * <p>What each piece of the builder buys:
     *
     * <ul>
     *   <li>{@code permissionContext} — the three-state permission engine. Read-only and
     *       state-writing tools carry ALLOW rules; {@code deploy_release} carries an ASK rule and
     *       therefore suspends the run into an AG-UI interrupt that CopilotKit renders as a
     *       confirmation card; {@code purge_production_data} carries a DENY rule that no mode or
     *       rule can lift. {@code refund_order} deliberately has <em>no</em> rule so its own
     *       {@code checkPermissions} decides based on the refund amount.
     *   <li>{@code enableTaskList()} — registers the built-in {@code todo_write} tool plus the
     *       per-turn task reminder. {@code WorkbenchEventMiddleware} mirrors the resulting task
     *       list into shared state, which is what drives the plan board and the STEP_STARTED /
     *       STEP_FINISHED frames.
     *   <li>{@code enableThinking} — reasoning deltas reach the browser as AG-UI
     *       {@code REASONING_MESSAGE_*} events.
     * </ul>
     *
     * <p>{@code PermissionMode.DEFAULT} means anything without a matching rule asks the user, so
     * new tools are safe by default rather than silently privileged.
     */
    private Agent createWorkbenchAgent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WorkbenchTools(workbenchStateRegistry, a2uiComposer));
        toolkit.registerAgentTool(new RefundOrderTool(workbenchStateRegistry));

        return ReActAgent.builder()
                .name("AgentScope_Workbench")
                .sysPrompt(WORKBENCH_SYS_PROMPT)
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(getRequiredApiKey())
                                .modelName(getModelName())
                                .stream(true)
                                .enableThinking(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .enableTaskList()
                .permissionContext(workbenchPermissionContext())
                .middleware(new WorkbenchEventMiddleware(workbenchStateRegistry))
                .maxIters(16)
                .build();
    }

    private static PermissionContextState workbenchPermissionContext() {
        PermissionContextState.Builder permissions =
                PermissionContextState.builder().mode(PermissionMode.DEFAULT);

        // Read-only and state-only tools: no reason to interrupt the user for these.
        for (String tool :
                List.of(
                        "query_service_metrics",
                        "update_workbench_brief",
                        "set_plan_goal",
                        "render_dashboard",
                        "confirmDemoAction",
                        "renderOrderCard",
                        "todo_write")) {
            permissions.addAllowRule(
                    tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "policy"));
        }

        // Production release always needs a human in the loop.
        permissions.addAskRule(
                "deploy_release",
                new PermissionRule("deploy_release", null, PermissionBehavior.ASK, "policy"));

        // Destructive and irreversible: blocked outright, and a deny rule cannot be bypassed.
        permissions.addDenyRule(
                "purge_production_data",
                new PermissionRule(
                        "purge_production_data", null, PermissionBehavior.DENY, "policy"));

        return permissions.build();
    }

    private static final String WORKBENCH_SYS_PROMPT =
            """
            你是 AgentScope 运维工作台的智能体，运行在 AG-UI 协议之上，前端是 CopilotKit。

            工作方式：
            1. 面对多步骤任务时，先调用 set_plan_goal 声明总目标，再用 todo_write 写出 3-6 条任务清单；
               推进过程中持续用 todo_write 更新状态（同一时刻只允许一条 in_progress）。前端计划看板会实时跟随。
            2. 需要数据时调用 query_service_metrics；需要把结论沉淀到共享状态时调用 update_workbench_brief。
            3. 需要用图形化方式展示指标、计划进度或风险结论时，调用 render_dashboard 动态生成界面，
               不要用 Markdown 表格代替它。
            4. deploy_release 是高危操作，会弹出人工确认；被拒绝时不要重试，直接说明已取消。
            5. purge_production_data 被安全策略永久禁止，用户要求删除生产数据时直接拒绝并解释原因。
            6. refund_order 的放行与否取决于金额，由工具自身判断，你只需如实发起调用。

            回答保持简洁，用中文，说明你做了什么以及下一步建议。不要假装某个操作已完成。
            """;

    /**
     * Create the default agent instance.
     *
     * <p>This agent is configured with:
     * <ul>
     *   <li>DashScope qwen-plus model with streaming enabled</li>
     *   <li>Example tools (get_weather, calculate)</li>
     *   <li>In-memory conversation memory</li>
     * </ul>
     */
    private Agent createDefaultAgent() {
        String apiKey = getRequiredApiKey();

        // Create toolkit with example tools
        Toolkit toolkit = new Toolkit();
        // Schema-only HITL tool: suspends the run so CopilotKit useInterrupt can resume it.
        // Do not also register this as a CopilotKit frontend tool — that auto-executes and
        // clears the pending interrupt before the interrupt UI can stay mounted.
        toolkit.registerSchema(requestHumanApprovalSchema());
        //        toolkit.registerTool(new ExampleTools());

        // Create the agent
        return ReActAgent.builder()
                .name("AG-UI Assistant")
                .sysPrompt(
                        "You are a helpful AI assistant exposed via the AG-UI protocol. When an"
                            + " operation needs explicit human confirmation, or the user asks for"
                            + " the approval interrupt demo, always call requestHumanApproval with"
                            + " a clear summary. Do not pretend the action is already done before"
                            + " approval. Be concise and helpful in your responses.")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(apiKey)
                                .modelName(getModelName())
                                .stream(true)
                                .enableThinking(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                //                .middleware(exampleCustomEventMiddleware())
                .maxIters(10)
                .build();
    }

    private static ToolSchema requestHumanApprovalSchema() {
        Map<String, Object> summaryProperty = new LinkedHashMap<>();
        summaryProperty.put("type", "string");
        summaryProperty.put("description", "需要用户确认的操作说明");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("summary", summaryProperty);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("summary"));

        return ToolSchema.builder()
                .name("requestHumanApproval")
                .description("当操作需要人工确认时调用，用户可以在聊天中批准或拒绝。")
                .parameters(parameters)
                .build();
    }

    /**
     * Create a simple chat agent without tools.
     *
     * <p>This agent is a pure conversational assistant.
     */
    private Agent createChatAgent() {
        String apiKey = getRequiredApiKey();

        return ReActAgent.builder()
                .name("Chat Assistant")
                .sysPrompt(
                        "You are a friendly conversational assistant. "
                                + "Engage in natural conversation and help users "
                                + "with general questions and discussions.")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(apiKey)
                                .modelName(getModelName())
                                .stream(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .middleware(exampleCustomEventMiddleware())
                .maxIters(1)
                .build();
    }

    /**
     * Create a calculator agent specialized for mathematical operations.
     */
    private Agent createCalculatorAgent() {
        String apiKey = getRequiredApiKey();

        // Create toolkit with only calculation tools
        Toolkit toolkit = new Toolkit();
        //        toolkit.registerTool(new ExampleTools());

        return ReActAgent.builder()
                .name("Calculator Agent")
                .sysPrompt(
                        "You are a mathematical assistant specialized in calculations. "
                                + "Use the calculate tool to perform mathematical operations. "
                                + "Always show your work and explain the results.")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(apiKey)
                                .modelName(getModelName())
                                .stream(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .middleware(exampleCustomEventMiddleware())
                .maxIters(5)
                .build();
    }

    private static AguiEvent enrichExampleEvent(
            AgentEvent source, AguiEvent event, long fallbackTimestamp) {
        Long timestamp = event.timestamp() != null ? event.timestamp() : fallbackTimestamp;
        Object rawEvent = event.rawEvent();
        if (rawEvent == null && source instanceof CustomEvent customEvent) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("agentEventType", source.getType().name());
            if (customEvent.getName() != null) {
                value.put("name", customEvent.getName());
            }
            rawEvent = value;
        }
        return AguiEvents.withBaseProperties(event, timestamp, rawEvent);
    }

    private static MiddlewareBase exampleCustomEventMiddleware() {
        return new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(
                    Agent agent,
                    RuntimeContext ctx,
                    AgentInput input,
                    Function<AgentInput, Flux<AgentEvent>> next) {
                CustomEvent event =
                        new CustomEvent("example_agent_event", exampleCustomEventValue(agent, ctx));
                return next.apply(input)
                        .concatMap(
                                agentEvent ->
                                        agentEvent instanceof AgentStartEvent
                                                ? Flux.just(agentEvent, event)
                                                : Flux.just(agentEvent));
            }
        };
    }

    private static Map<String, Object> exampleCustomEventValue(Agent agent, RuntimeContext ctx) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("agentName", agent.getName());
        if (ctx != null && ctx.getSessionId() != null) {
            value.put("sessionId", ctx.getSessionId());
        }
        return value;
    }

    private String getRequiredApiKey() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "DASHSCOPE_API_KEY environment variable is required. "
                            + "Please set it before starting the application.");
        }
        return apiKey;
    }

    private String getModelName() {
        String modelName = System.getenv("DASHSCOPE_MODEL");
        if (modelName == null || modelName.isEmpty()) {
            return "qwen3.5-flash";
        }
        return modelName;
    }

    @PostConstruct
    public void init() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // CopilotKit rejects JSON null fields on some AG-UI event payloads.
        objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        JsonUtils.setJsonCodec(new JacksonJsonCodec(objectMapper));
    }
}
