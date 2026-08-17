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
import java.util.ArrayList;
import java.util.List;

/**
 * Filter that admits a skill only when its sidecar's {@code environments} list contains the
 * configured environment ({@code prod}, {@code staging}, …). Unlike the canary / allow-list
 * filters, this one applies to <em>every</em> skill — including pre-existing ones — so the
 * deployment-environment story is consistent across both agent-authored and hand-crafted
 * skills.
 *
 * <p>For hand-crafted skills that have no sidecar entry, this filter passes them through
 * unconditionally. The expectation is that authoritative ops will set
 * {@code environments: [prod]} on the sidecar entry by hand once they've been audited.
 */
@SuppressWarnings("deprecation")
public class EnvironmentFilter implements SkillVisibilityFilter {

    private final String env;
    private final SkillUsageStore usageStore;

    public EnvironmentFilter(String env, SkillUsageStore usageStore) {
        this.env = env != null ? env : "prod";
        this.usageStore = usageStore;
    }

    @Override
    public List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx) {
        if (all == null || all.isEmpty() || usageStore == null) {
            return all == null ? List.of() : all;
        }
        List<AgentSkill> out = new ArrayList<>(all.size());
        for (AgentSkill skill : all) {
            if (skill == null || skill.getName() == null) {
                continue;
            }
            SkillUsageRecord rec = usageStore.get(skill.getName()).orElse(null);
            if (rec == null) {
                // No sidecar entry → external / pre-existing skill; let through.
                out.add(skill);
                continue;
            }
            List<String> envs = rec.environments();
            if (envs == null || envs.isEmpty() || envs.contains(env)) {
                out.add(skill);
            }
            // else filter out (e.g. environments=[draft] never matches env=prod)
        }
        return out;
    }
}
