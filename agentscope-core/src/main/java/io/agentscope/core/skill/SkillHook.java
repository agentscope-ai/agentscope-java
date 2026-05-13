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
package io.agentscope.core.skill;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import reactor.core.publisher.Mono;

/**
 * Injects the skill catalog prompt into the unified system message on {@link PreCallEvent} via
 * {@link PreCallEvent#appendSystemContent(String)}.
 *
 * <p>Uses priority {@link #SKILL_HOOK_PRIORITY} so that, in typical {@code HarnessAgent} wiring,
 * this hook runs after {@code SubagentsHook} (80) and before {@code WorkspaceContextHook} (900),
 * yielding append order: base prompt → subagents → skills → workspace context.
 *
 * <p>The skill prompt is appended to the transient system message and is never stored in
 * the agent's persistent {@code Memory}.
 */
public class SkillHook implements Hook {

    /**
     * Runs after subagent prompt injection and before workspace context injection in the default
     * harness hook chain.
     */
    public static final int SKILL_HOOK_PRIORITY = 85;

    private final SkillBox skillBox;

    public SkillHook(SkillBox skillBox) {
        this.skillBox = skillBox;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            String skillPrompt = skillBox.getSkillPrompt();
            if (skillPrompt != null && !skillPrompt.isEmpty()) {
                preCallEvent.appendSystemContent(skillPrompt);
            }
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return SKILL_HOOK_PRIORITY;
    }
}
