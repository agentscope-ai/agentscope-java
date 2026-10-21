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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentExecution;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Harness host hook: all child inference is admitted as native Turns, never a local callback. */
public final class ManagedSubagentExecution implements SubagentExecution {
    private final HarnessAgent owner;
    private final List<String> skills;
    private final ManagedSubagents children;
    private final ManagedToolRequests requests;
    private final ObjectMapper mapper;

    public ManagedSubagentExecution(
            HarnessAgent owner,
            List<String> skills,
            ManagedSubagents children,
            ManagedToolRequests requests,
            ObjectMapper mapper) {
        this.owner = owner;
        this.skills = List.copyOf(skills);
        this.children = children;
        this.requests = requests;
        this.mapper = mapper;
    }

    @Override
    public boolean ownsChildState() {
        return true;
    }

    @Override
    public String childSessionId(RuntimeContext parent) {
        var execution = parent.get(ManagedTurnExecution.class);
        var call = parent.get(ToolUseBlock.class);
        if (execution == null || call == null)
            throw new IllegalStateException("Managed child needs an active parent tool call");
        String agentId = String.valueOf(call.getInput().get("agent_id"));
        var declaration = manager(parent).getDeclaration(agentId);
        String identity = execution.turnId() + ":" + call.getId();
        if (declaration.isPresent() && declaration.get().isPersistSession()) {
            Object label = call.getInput().get("label");
            identity =
                    "persistent:"
                            + agentId
                            + ":"
                            + (label instanceof String text ? text.trim() : "");
        }
        return UUID.nameUUIDFromBytes(
                        (execution.scope().messageGroup() + ":child:" + identity)
                                .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    @Override
    public Mono<Msg> invoke(
            Agent agent,
            RuntimeContext parent,
            RuntimeContext child,
            Supplier<Mono<Msg>> localInvocation) {
        return Mono.fromCallable(
                        () -> {
                            var invocation = parent.get(Invocation.class);
                            var execution = parent.get(ManagedTurnExecution.class);
                            if (invocation == null
                                    || execution == null
                                    || !(agent instanceof HarnessAgent harness))
                                throw new IllegalStateException(
                                        "Managed subagent requires a declared Harness child and"
                                                + " native parent call");
                            var childScope =
                                    new ManagedScope(
                                            execution.scope().tenant(),
                                            execution.scope().namespace(),
                                            child.getSessionId());
                            var known = children.parent(childScope);
                            String definition;
                            if (known.isPresent()) {
                                if (!known.get().parent().equals(execution.scope())
                                        || !known.get()
                                                .specification()
                                                .agentId()
                                                .equals(invocation.agentId()))
                                    throw new ManagedStateException(
                                            "subagent_identity_conflict",
                                            "Child Session belongs to another parent or Agent");
                                definition = known.get().specification().frozenDefinition();
                            } else {
                                var declaration =
                                        manager(parent).getDeclaration(invocation.agentId());
                                String modelReference =
                                        declaration
                                                .map(value -> value.getModel())
                                                .filter(value -> !value.isBlank())
                                                .orElse(null);
                                if (harness.getModel() != owner.getModel()
                                        && modelReference == null)
                                    throw new ManagedStateException(
                                            "subagent_model_definition_missing",
                                            "Custom child model has no serializable model"
                                                    + " reference");
                                if (modelReference == null) {
                                    var parentCall = children.parent(execution.scope());
                                    if (parentCall.isPresent())
                                        modelReference =
                                                mapper.readValue(
                                                                parentCall
                                                                        .get()
                                                                        .specification()
                                                                        .frozenDefinition(),
                                                                ManagedSubagentDefinition.class)
                                                        .modelReference();
                                }
                                var allowedSkills =
                                        declaration
                                                .filter(value -> !value.getSkills().isEmpty())
                                                .map(
                                                        value ->
                                                                skills.stream()
                                                                        .filter(
                                                                                value.getSkills()
                                                                                        ::contains)
                                                                        .toList())
                                                .orElse(skills);
                                var state = parent.getAgentState();
                                if (state == null)
                                    throw new IllegalStateException(
                                            "Managed parent has no active native state");
                                var codec = JsonUtils.getJsonCodec();
                                definition =
                                        json(
                                                new ManagedSubagentDefinition(
                                                        1,
                                                        execution.scope().messageGroup(),
                                                        harness.getName(),
                                                        harness.getDelegate().getSysPrompt(),
                                                        modelReference,
                                                        harness.getMaxIters(),
                                                        codec.toJson(
                                                                harness.getDelegate()
                                                                        .getGenerateOptions()),
                                                        codec.toJson(state.getPermissionContext()),
                                                        codec.toJson(state.getPlanModeContext()),
                                                        harness.getToolkit().getToolNames().stream()
                                                                .filter(
                                                                        name ->
                                                                                !name.startsWith(
                                                                                        "task."))
                                                                .filter(
                                                                        name ->
                                                                                !ManagedTaskRepository
                                                                                        .LOCAL_TOOLS
                                                                                        .contains(
                                                                                                name))
                                                                .sorted()
                                                                .toList(),
                                                        allowedSkills));
                            }
                            var specification =
                                    new ManagedSubagents.Specification(
                                            invocation.agentId(),
                                            invocation.prompt(),
                                            invocation.resultHeader(),
                                            definition,
                                            childScope.sessionId());
                            var reply =
                                    requests.request(
                                            parent,
                                            ManagedSubagents.OPERATION,
                                            Map.of("subagent", specification),
                                            ManagedSubagents.Reply.class);
                            if (!"succeeded".equals(reply.status()))
                                throw new IllegalStateException(reply.reason());
                            return JsonUtils.getJsonCodec()
                                    .fromJson(reply.messageJson(), Msg.class);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String json(Object value) throws JsonProcessingException {
        return mapper.writeValueAsString(value);
    }

    private DefaultAgentManager manager(RuntimeContext context) {
        var scoped = context.get(AgentSpawnTool.CTX_AGENT_MANAGER, DefaultAgentManager.class);
        return scoped == null ? owner.getSubagentAgentManager() : scoped;
    }
}
