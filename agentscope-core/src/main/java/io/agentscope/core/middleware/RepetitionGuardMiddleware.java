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
package io.agentscope.core.middleware;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.SchemaOnlyTool;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

/** Opt-in guard for consecutive identical local tool requests in one agent invocation. */
public final class RepetitionGuardMiddleware implements MiddlewareBase {
    private static final Object BATCH_CHECK = new Object();
    private static final ObjectWriter SORTED_JSON =
            new ObjectMapper().writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final int warnAt;
    private final int stopAt;
    private final Set<String> excluded;

    /** Warn on the third identical request and stop before dispatching the fifth. */
    public RepetitionGuardMiddleware() {
        this(3, 5, Set.of());
    }

    public RepetitionGuardMiddleware(int warnAt, int stopAt, Set<String> excludedTools) {
        if (warnAt < 1 || stopAt <= warnAt) {
            throw new IllegalArgumentException("Require 1 <= warnAt < stopAt");
        }
        this.warnAt = warnAt;
        this.stopAt = stopAt;
        this.excluded = Set.copyOf(excludedTools);
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return Set.of(
                ExtensionPoint.ON_AGENT, ExtensionPoint.ON_ACTING, ExtensionPoint.ON_REASONING);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        // A private subscriber-context entry, not an agent/session field. Resubscribing
        // starts fresh; error, completion and cancellation need no shared-map cleanup.
        return Flux.defer(() -> next.apply(input)).contextWrite(c -> c.put(this, new Streak()));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                view -> {
                    Streak streak = view.get(this);
                    // onActing wraps the permission gate. Evaluate its proposed batch only once
                    // the runtime has determined which calls are actually eligible for dispatch.
                    BatchCheck batch = new BatchCheck(ctx, agent, streak);
                    return next.apply(input)
                            .contextWrite(c -> c.put(BATCH_CHECK, batch))
                            .doOnComplete(
                                    () -> {
                                        if (!batch.evaluated) streak.reset();
                                    });
                });
    }

    /**
     * Runtime hook for the permission-resolved acting batch.
     *
     * @hidden
     */
    public static boolean stopsDispatch(
            ContextView view,
            RuntimeContext ctx,
            List<ToolUseBlock> requested,
            List<ToolUseBlock> approved) {
        BatchCheck batch = view.getOrDefault(BATCH_CHECK, null);
        return batch != null && batch.owner == ctx && batch.shouldStop(requested, approved);
    }

    private final class BatchCheck {
        final RuntimeContext owner;
        final Agent agent;
        final Streak streak;
        boolean evaluated;

        BatchCheck(RuntimeContext owner, Agent agent, Streak streak) {
            this.owner = owner;
            this.agent = agent;
            this.streak = streak;
        }

        boolean shouldStop(List<ToolUseBlock> requested, List<ToolUseBlock> approved) {
            evaluated = true;
            Set<String> eligible = new HashSet<>();
            for (ToolUseBlock call : approved) {
                eligible.add(call.getId());
            }
            for (ToolUseBlock call : requested) {
                String name = call.getName();
                if (!eligible.contains(call.getId())
                        || excluded.contains(name)
                        || isExternal(name)) {
                    streak.reset();
                } else if (streak.record(call) >= stopAt) {
                    return true;
                }
            }
            return false;
        }

        private boolean isExternal(String name) {
            return agent instanceof ReActAgent react
                    && react.getToolkit().getTool(name, owner.getToolRequestConfig())
                            instanceof SchemaOnlyTool;
        }
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                view -> {
                    Streak streak = view.get(this);
                    if (!streak.warn) {
                        return next.apply(input);
                    }
                    streak.warn = false;
                    List<Msg> messages = new ArrayList<>(input.messages());
                    messages.add(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .name("system")
                                    .textContent(
                                            "You have repeated the same tool call. Try a different"
                                                    + " approach.")
                                    .metadata(
                                            Map.of(
                                                    Msg.METADATA_SYNTHETIC,
                                                    true,
                                                    Msg.METADATA_REMINDER_KIND,
                                                    "repeated_tool_call"))
                                    .build());
                    return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
                });
    }

    private final class Streak {
        String name;
        byte[] last;
        int count;
        boolean warn;

        void reset() {
            name = null;
            last = null;
            count = 0;
        }

        int record(ToolUseBlock call) {
            byte[] digest;
            try {
                digest =
                        MessageDigest.getInstance("SHA-256")
                                .digest(SORTED_JSON.writeValueAsBytes(call.getInput()));
            } catch (JsonProcessingException ex) {
                // Non-JSON extension values have no comparable canonical representation.
                reset();
                return 0;
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
            if (!call.getName().equals(name) || !Arrays.equals(digest, last)) {
                reset();
                name = call.getName();
                last = digest;
            }
            if (++count == warnAt) {
                warn = true;
            }
            return count;
        }
    }
}
