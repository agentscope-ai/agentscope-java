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
package io.agentscope.builder.web.toolbus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.web.catalog.spec.AgentSpecCodec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes;
import io.agentscope.builder.web.managed.AgentVersionSnapshot;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedTurnContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Harness middleware that enforces per-tool permission policies. Tools configured with
 * {@code always_ask} pause execution until the user confirms via {@link ToolConfirmationCoordinator}.
 */
@Component
public class ToolConfirmationMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolConfirmationMiddleware.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    public static final String POLICY_ALWAYS_ALLOW = "always_allow";
    public static final String POLICY_ALWAYS_ASK = "always_ask";
    public static final String POLICY_DENY = "deny";
    private static final String POLICY_DENY_MESSAGE =
            "Tool execution denied by the agent's configured permission policy.";
    private static final String HUMAN_DENY_MESSAGE =
            "Tool execution was rejected by human approval or the approval timed out.";
    private static final String POLICY_LOAD_DENY_MESSAGE =
            "Tool execution denied because the pinned permission policy could not be loaded.";
    private static final String INVALID_POLICY_DENY_MESSAGE =
            "Tool execution denied because its permission policy is invalid.";

    private final ToolConfirmationCoordinator coordinator;
    private final DataSessionService sessionService;

    public ToolConfirmationMiddleware(
            ToolConfirmationCoordinator coordinator, @Lazy DataSessionService sessionService) {
        this.coordinator = coordinator;
        this.sessionService = sessionService;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        String sessionId = resolveSessionId(ctx);
        if (sessionId == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        PolicySet policies = resolvePolicies(sessionId);
        List<ToolUseBlock> allowed = new ArrayList<>();
        List<DeniedCall> denied = new ArrayList<>();
        for (ToolUseBlock toolUse : input.toolCalls()) {
            String policy = policies.policyFor(toolUse.getName());
            if (policies.loadFailed() || POLICY_DENY.equals(policy)) {
                log.info(
                        "Tool execution denied by policy: session={}, tool={}, toolUseId={}",
                        sessionId,
                        toolUse.getName(),
                        toolUse.getId());
                denied.add(
                        new DeniedCall(
                                toolUse,
                                policies.loadFailed()
                                        ? POLICY_LOAD_DENY_MESSAGE
                                        : POLICY_DENY_MESSAGE));
                continue;
            }
            if (POLICY_ALWAYS_ASK.equals(policy)) {
                Map<String, Object> toolInput = new LinkedHashMap<>();
                if (toolUse.getInput() != null) {
                    toolInput.putAll(toolUse.getInput());
                }
                ToolConfirmationCoordinator.ConfirmationDecision decision =
                        coordinator.awaitDecision(
                                sessionId,
                                toolUse.getId(),
                                toolUse.getName(),
                                toolInput,
                                ctx.get(ManagedTurnContext.class));
                if (decision.allow()) {
                    allowed.add(toolUse);
                } else {
                    denied.add(
                            new DeniedCall(
                                    toolUse,
                                    decision.reason() != null
                                            ? decision.reason()
                                            : HUMAN_DENY_MESSAGE));
                    log.info(
                            "Tool execution denied: session={}, tool={}, toolUseId={}",
                            sessionId,
                            toolUse.getName(),
                            toolUse.getId());
                }
            } else if (POLICY_ALWAYS_ALLOW.equals(policy)) {
                allowed.add(toolUse);
            } else {
                // Unknown/null policy values are configuration errors, never implicit grants.
                denied.add(new DeniedCall(toolUse, INVALID_POLICY_DENY_MESSAGE));
                log.warn(
                        "Tool execution denied due to invalid policy: session={}, tool={},"
                                + " toolUseId={}, policy={}",
                        sessionId,
                        toolUse.getName(),
                        toolUse.getId(),
                        policy);
            }
        }
        if (denied.isEmpty()) {
            return next.apply(input);
        }
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        Flux<AgentEvent> deniedFlux = deniedFlux(agent, state, denied);
        if (allowed.isEmpty()) {
            return deniedFlux;
        }
        return deniedFlux.concatWith(next.apply(new ActingInput(allowed)));
    }

    private static Flux<AgentEvent> deniedFlux(
            Agent agent, AgentState state, List<DeniedCall> denied) {
        return Flux.defer(
                () -> {
                    List<AgentEvent> events = new ArrayList<>();
                    for (DeniedCall deniedCall : denied) {
                        ToolUseBlock call = deniedCall.call();
                        ToolResultBlock result =
                                ToolResultBlock.text(deniedCall.message())
                                        .withIdAndName(call.getId(), call.getName())
                                        .withState(ToolResultState.DENIED);
                        Msg message =
                                ToolResultMessageBuilder.buildToolResultMsg(
                                        result, call, agent.getName());
                        state.contextMutable().add(message);
                        events.add(
                                new ToolResultStartEvent(
                                        state.getReplyId(), call.getId(), call.getName()));
                        events.add(
                                new ToolResultTextDeltaEvent(
                                        state.getReplyId(),
                                        call.getId(),
                                        call.getName(),
                                        deniedCall.message()));
                        events.add(
                                new ToolResultEndEvent(
                                        state.getReplyId(),
                                        call.getId(),
                                        call.getName(),
                                        ToolResultState.DENIED));
                    }
                    return Flux.fromIterable(events);
                });
    }

    private record DeniedCall(ToolUseBlock call, String message) {}

    private PolicySet resolvePolicies(String sessionId) {
        try {
            SessionResolveResult resolved = sessionService.resolve(sessionId);
            if (resolved == null
                    || resolved.session() == null
                    || resolved.agentSnapshot() == null
                    || resolved.agentSnapshot().isEmpty()) {
                log.warn(
                        "Control-plane snapshot missing while resolving policies for {}",
                        sessionId);
                return PolicySet.failed();
            }
            AgentVersionSnapshot snapshot =
                    JSON_MAPPER.convertValue(resolved.agentSnapshot(), AgentVersionSnapshot.class);
            return policySet(snapshot.tools());
        } catch (Exception ex) {
            log.warn("Could not resolve policies for session {}: {}", sessionId, ex.getMessage());
            return PolicySet.failed();
        }
    }

    private static PolicySet policySet(List<AgentSpecTypes.AgentToolset> tools) {
        String defaultPolicy = POLICY_ALWAYS_ALLOW;
        if (tools != null) {
            for (AgentSpecTypes.AgentToolset toolset : tools) {
                if (toolset == null || !AgentSpecTypes.TOOLSET_AGENT.equals(toolset.type())) {
                    continue;
                }
                if (toolset.defaultConfig() != null
                        && toolset.defaultConfig().permissionPolicy() != null
                        && toolset.defaultConfig().permissionPolicy().type() != null) {
                    defaultPolicy = toolset.defaultConfig().permissionPolicy().type();
                }
                if (toolset.defaultConfig() != null
                        && Boolean.FALSE.equals(toolset.defaultConfig().enabled()))
                    defaultPolicy = POLICY_DENY;
            }
        }
        return new PolicySet(defaultPolicy, AgentSpecCodec.toPermissionPolicyMap(tools), false);
    }

    private record PolicySet(
            String defaultPolicy, Map<String, String> overrides, boolean loadFailed) {

        static PolicySet failed() {
            return new PolicySet(POLICY_DENY, Map.of(), true);
        }

        String policyFor(String toolName) {
            if (overrides.containsKey(toolName)) return overrides.get(toolName);
            int separator = toolName.indexOf("__");
            if (separator > 0) {
                return overrides.getOrDefault(
                        toolName.substring(0, separator) + "__*", POLICY_ALWAYS_ASK);
            }
            return defaultPolicy;
        }
    }

    private static String resolveSessionId(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        var identity = ctx.get(io.agentscope.builder.web.managed.ManagedSessionIdentity.class);
        if (identity != null) return identity.sessionId();
        if (ctx.getSessionId() != null) {
            return ctx.getSessionId();
        }
        return ctx.getUserId();
    }
}
