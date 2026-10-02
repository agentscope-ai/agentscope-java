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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Bumps {@link SkillUsageStore} counters whenever the agent invokes a SkillBox-registered skill
 * loader (e.g. {@code load_skill_through_path}). Provenance gating happens inside the store —
 * only skills tagged {@code created_by="agent"} or {@code "agent-draft"} are recorded.
 *
 * <p>Counter bumping is best-effort and runs eagerly when {@code onActing} is entered (before
 * the actual tool call returns). The semantics are "the model decided to invoke this skill on
 * this turn" — not "the call succeeded". This matches hermes-agent's telemetry shape and is
 * cheap to compute.
 */
public class SkillUsageMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageMiddleware.class);

    /** Tool names whose invocation counts as a "view" of a skill. */
    private static final Set<String> VIEW_TOOL_NAMES =
            Set.of("load_skill_through_path", "read_skill");

    /**
     * Tool names whose invocation counts as a "use" of a skill. Reserved for future SkillBox
     * surfaces (e.g. an explicit {@code use_skill}); empty today so {@code bumpUse} stays unused.
     */
    private static final Set<String> USE_TOOL_NAMES = Set.of("use_skill");

    private final SkillUsageStore usageStore;

    public SkillUsageMiddleware(SkillUsageStore usageStore) {
        this.usageStore = java.util.Objects.requireNonNull(usageStore, "usageStore");
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input != null && input.toolCalls() != null) {
            for (ToolUseBlock call : input.toolCalls()) {
                trackInvocation(call);
            }
        }
        return next.apply(input);
    }

    /**
     * If {@code call} targets a SkillBox loader / user, extract the {@code skillId} parameter
     * (or fallback equivalents) and bump the right counter on the store. Failures are swallowed
     * — telemetry must never break the agent loop.
     */
    private void trackInvocation(ToolUseBlock call) {
        if (call == null || call.getName() == null) {
            return;
        }
        String toolName = call.getName();
        boolean isView = VIEW_TOOL_NAMES.contains(toolName);
        boolean isUse = USE_TOOL_NAMES.contains(toolName);
        if (!isView && !isUse) {
            return;
        }
        String skillName = extractSkillName(call);
        if (skillName == null || skillName.isBlank()) {
            return;
        }
        try {
            if (isView) {
                usageStore.bumpView(skillName);
            } else {
                usageStore.bumpUse(skillName);
            }
        } catch (Exception e) {
            log.debug(
                    "SkillUsageMiddleware bump for tool={} skill={} failed: {}",
                    toolName,
                    skillName,
                    e.getMessage());
        }
    }

    /**
     * Pulls the skill identifier out of a tool-use block. {@code skillId} is the canonical key
     * used by the SkillBox loader; {@code skill_id} / {@code name} are tolerated as fallbacks
     * for forward compatibility with potential renames.
     */
    private static String extractSkillName(ToolUseBlock call) {
        Map<String, Object> input = call.getInput();
        if (input == null) {
            return null;
        }
        for (String key : new String[] {"skillId", "skill_id", "name"}) {
            Object v = input.get(key);
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    // Suppress unused warning for the future-proof "use" channel.
    @SuppressWarnings("unused")
    private static Mono<Void> noop() {
        return Mono.empty();
    }
}
