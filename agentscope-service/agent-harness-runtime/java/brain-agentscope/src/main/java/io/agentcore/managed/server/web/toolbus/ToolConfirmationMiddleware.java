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
package io.agentcore.managed.server.web.toolbus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedTurnExecution;
import io.agentcore.managed.server.web.catalog.spec.AgentSpecCodec;
import io.agentcore.managed.server.web.catalog.spec.AgentSpecTypes;
import io.agentcore.managed.server.web.managed.AgentVersionSnapshot;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.util.MessageUtils;
import io.agentscope.extensions.judge.jev.JevConfirmedCalls;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Harness middleware that enforces per-tool permission policies. Tools configured with
 * {@code always_ask} pause native execution until a Session reply supplies confirmation results.
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
    private static final String POLICY_LOAD_DENY_MESSAGE =
            "Tool execution denied because the pinned permission policy could not be loaded.";
    private static final String INVALID_POLICY_DENY_MESSAGE =
            "Tool execution denied because its permission policy is invalid.";

    private final ManagedCatalog catalog;

    public ToolConfirmationMiddleware(ManagedCatalog catalog) {
        this.catalog = catalog;
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
        PolicySet policies = resolvePolicies(ctx, sessionId);
        List<ToolUseBlock> allowed = new ArrayList<>();
        List<DeniedCall> denied = new ArrayList<>();
        List<ToolUseBlock> asking = new ArrayList<>();
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
                if (toolUse.getState() == ToolCallState.ALLOWED) {
                    JevConfirmedCalls.remember(ctx, JevConfirmedCalls.snapshot(toolUse));
                    allowed.add(toolUse);
                } else asking.add(toolUse.withState(ToolCallState.ASKING));
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
        if (!asking.isEmpty()) {
            return Flux.defer(
                            () -> {
                                AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
                                var replacements = new LinkedHashMap<String, ToolUseBlock>();
                                for (var call : asking) replacements.put(call.getId(), call);
                                if (!MessageUtils.replaceToolUseBlocks(
                                        state.contextMutable(), replacements))
                                    return Flux.error(
                                            new IllegalStateException(
                                                    "Confirmation tool calls are missing from"
                                                            + " native state"));
                                var recorder = SessionRecorder.from(ctx);
                                if (recorder == null)
                                    return Flux.error(
                                            new IllegalStateException(
                                                    "Managed confirmation requires a native"
                                                            + " recorder"));
                                for (var call : asking)
                                    recorder.append(
                                            "interaction/requested",
                                            Map.of(
                                                    "requestId",
                                                    call.getId(),
                                                    "kind",
                                                    "confirmation",
                                                    "call",
                                                    call));
                                recorder.checkpointNow(state, "managed_confirmation");
                                return deniedFlux(agent, state, denied)
                                        .concatWith(
                                                Flux.just(
                                                        new RequireUserConfirmEvent(
                                                                state.getReplyId(), asking),
                                                        new RequestStopEvent(
                                                                "service confirmation required",
                                                                GenerateReason.PERMISSION_ASKING)));
                            })
                    .subscribeOn(Schedulers.boundedElastic());
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

    private PolicySet resolvePolicies(RuntimeContext context, String sessionId) {
        try {
            var execution = context.get(ManagedTurnExecution.class);
            if (execution != null && !sessionId.equals(execution.scope().sessionId()))
                return PolicySet.failed();
            SessionResolveResult resolved =
                    execution == null
                            ? catalog.resolve(sessionId)
                            : catalog.resolve(execution.scope());
            if (resolved == null
                    || resolved.session() == null
                    || resolved.agentSnapshot() == null
                    || resolved.agentSnapshot().isEmpty()) {
                log.warn(
                        "Frozen Managed snapshot missing while resolving policies for {}",
                        sessionId);
                return PolicySet.failed();
            }
            AgentVersionSnapshot snapshot =
                    JSON_MAPPER.convertValue(resolved.agentSnapshot(), AgentVersionSnapshot.class);
            var policies = policySet(snapshot.tools());
            // These tools are installed by the service from a private task context,
            // independently of the Agent's default built-in tool switch. Explicit
            // per-tool policies still take precedence, and MCP checks current task authority.
            if (resolved.executionContext() != null
                    && resolved.executionContext().get("taskContext") instanceof Map<?, ?> task
                    && task.get("taskToken") instanceof String token
                    && !token.isBlank()
                    && task.get("availableActions") instanceof List<?> actions) {
                var overrides = new LinkedHashMap<>(policies.overrides());
                for (var action : actions)
                    if (action instanceof String name && !name.isBlank())
                        overrides.putIfAbsent(name, POLICY_ALWAYS_ALLOW);
                return new PolicySet(policies.defaultPolicy(), overrides, false);
            }
            return policies;
        } catch (Exception ex) {
            log.warn("Could not resolve policies for session {}: {}", sessionId, ex.getMessage());
            return PolicySet.failed();
        }
    }

    private static PolicySet policySet(List<AgentSpecTypes.AgentToolset> tools) {
        return new PolicySet(
                AgentSpecCodec.defaultAgentPermissionPolicy(tools),
                AgentSpecCodec.toPermissionPolicyMap(tools),
                false);
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
        var identity =
                ctx.get(io.agentcore.managed.server.web.managed.ManagedSessionIdentity.class);
        if (identity != null) return identity.sessionId();
        if (ctx.getSessionId() != null) {
            return ctx.getSessionId();
        }
        return ctx.getUserId();
    }
}
