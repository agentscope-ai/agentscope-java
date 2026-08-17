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
package io.agentscope.harness.agent.skill.curator;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import java.util.Set;

/**
 * Visible only when the agent-created skill's name is in the allow-list. Useful for letting
 * SRE pin a small set of agent-authored skills for an internal beta channel without
 * generally exposing every promoted skill.
 */
public class AllowListFilter extends AbstractAgentCreatedFilter {

    private final Set<String> allow;

    public AllowListFilter(Set<String> allow, SkillUsageStore usageStore) {
        super(usageStore);
        this.allow = allow != null ? Set.copyOf(allow) : Set.of();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected boolean shouldPassForAgentCreated(
            AgentSkill skill, SkillUsageRecord rec, RuntimeContext ctx) {
        return allow.contains(skill.getName());
    }
}
