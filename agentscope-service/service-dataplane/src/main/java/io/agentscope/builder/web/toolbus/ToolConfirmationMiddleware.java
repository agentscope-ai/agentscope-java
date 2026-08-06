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

import io.agentscope.builder.web.catalog.spec.AgentSpecCodec;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.service.AgentVersionService;
import io.agentscope.builder.web.persistence.jpa.AgentEntityRepository;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
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

    public static final String POLICY_ALWAYS_ALLOW = "always_allow";
    public static final String POLICY_ALWAYS_ASK = "always_ask";
    public static final String POLICY_DENY = "deny";

    private final ToolConfirmationCoordinator coordinator;
    private final DataSessionService sessionService;
    private final AgentEntityRepository agentRepository;
    private final AgentVersionService versionService;

    public ToolConfirmationMiddleware(
            ToolConfirmationCoordinator coordinator,
            @Lazy DataSessionService sessionService,
            AgentEntityRepository agentRepository,
            AgentVersionService versionService) {
        this.coordinator = coordinator;
        this.sessionService = sessionService;
        this.agentRepository = agentRepository;
        this.versionService = versionService;
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
        Map<String, String> policies = resolvePolicies(sessionId);
        List<ToolUseBlock> allowed = new ArrayList<>();
        for (ToolUseBlock toolUse : input.toolCalls()) {
            String policy = policies.get(toolUse.getName());
            if (POLICY_DENY.equals(policy)) {
                log.info(
                        "Tool execution denied by policy: session={}, tool={}, toolUseId={}",
                        sessionId,
                        toolUse.getName(),
                        toolUse.getId());
                continue;
            }
            if (POLICY_ALWAYS_ASK.equals(policy)) {
                Map<String, Object> toolInput = new LinkedHashMap<>();
                if (toolUse.getInput() != null) {
                    toolInput.putAll(toolUse.getInput());
                }
                boolean confirmed =
                        coordinator.awaitConfirmation(
                                sessionId, toolUse.getId(), toolUse.getName(), toolInput);
                if (confirmed) {
                    allowed.add(toolUse);
                } else {
                    log.info(
                            "Tool execution denied: session={}, tool={}, toolUseId={}",
                            sessionId,
                            toolUse.getName(),
                            toolUse.getId());
                }
            } else {
                // always_allow or unspecified → allow
                allowed.add(toolUse);
            }
        }
        if (allowed.isEmpty()) {
            return Flux.empty();
        }
        if (allowed.size() == input.toolCalls().size()) {
            return next.apply(input);
        }
        return next.apply(new ActingInput(allowed));
    }

    private Map<String, String> resolvePolicies(String sessionId) {
        try {
            ManagedSessionDto session = sessionService.requireById(sessionId);
            return policiesForSession(session);
        } catch (Exception ex) {
            log.debug("Could not resolve policies for session {}: {}", sessionId, ex.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> policiesForSession(ManagedSessionDto session) {
        String ownerId = session.agentOwnerId();
        if (ownerId == null) {
            return Map.of();
        }
        if (session.agentVersion() != null) {
            try {
                var version =
                        versionService.getVersion(
                                ownerId, session.agentId(), session.agentVersion());
                var snapshot = versionService.fromJson(version.getSnapshotJson());
                Map<String, String> policies =
                        AgentSpecCodec.toPermissionPolicyMap(snapshot.tools());
                if (!policies.isEmpty()) {
                    return policies;
                }
            } catch (Exception ex) {
                log.debug(
                        "Could not load version policies for session {}: {}",
                        session.id(),
                        ex.getMessage());
            }
        }
        return agentRepository
                .findByOwnerIdAndAgentId(ownerId, session.agentId())
                .map(
                        entity -> {
                            try {
                                var snapshot = versionService.snapshotFromEntity(entity);
                                return AgentSpecCodec.toPermissionPolicyMap(snapshot.tools());
                            } catch (Exception ex) {
                                return Map.<String, String>of();
                            }
                        })
                .orElse(Map.of());
    }

    private static String resolveSessionId(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (ctx.getSessionId() != null) {
            return ctx.getSessionId();
        }
        return ctx.getUserId();
    }
}
