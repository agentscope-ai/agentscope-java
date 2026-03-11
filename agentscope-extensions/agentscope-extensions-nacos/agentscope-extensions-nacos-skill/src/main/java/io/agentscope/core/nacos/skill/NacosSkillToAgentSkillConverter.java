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
package io.agentscope.core.nacos.skill;

import com.alibaba.nacos.api.ai.model.skills.Skill;
import com.alibaba.nacos.api.ai.model.skills.SkillResource;
import io.agentscope.core.skill.AgentSkill;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts Nacos AI {@link Skill} to AgentScope {@link AgentSkill}.
 */
public final class NacosSkillToAgentSkillConverter {

    private static final String NO_DESCRIPTION = "(no description)";
    private static final String NO_INSTRUCTION = "(no instruction)";

    private NacosSkillToAgentSkillConverter() {}

    /**
     * Converts a Nacos Skill to an AgentSkill.
     *
     * @param nacosSkill the Nacos Skill (must not be null)
     * @param source     the source identifier for the resulting AgentSkill (e.g. "nacos:public")
     * @return the converted AgentSkill
     */
    public static AgentSkill toAgentSkill(Skill nacosSkill, String source) {
        if (nacosSkill == null) {
            throw new IllegalArgumentException("Nacos Skill cannot be null");
        }
        String name = blankToDefault(nacosSkill.getName(), "unknown");
        String description = blankToDefault(nacosSkill.getDescription(), NO_DESCRIPTION);
        String skillContent = blankToDefault(nacosSkill.getInstruction(), NO_INSTRUCTION);
        Map<String, String> resources = toResourceMap(nacosSkill.getResource());
        return new AgentSkill(name, description, skillContent, resources, source);
    }

    private static String blankToDefault(String value, String defaultValue) {
        return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
    }

    private static Map<String, String> toResourceMap(Map<String, SkillResource> resourceMap) {
        if (resourceMap == null || resourceMap.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, String> result = new HashMap<>(resourceMap.size());
        for (Map.Entry<String, SkillResource> e : resourceMap.entrySet()) {
            String key = e.getKey() != null ? e.getKey() : "resource";
            SkillResource res = e.getValue();
            String content = (res != null && res.getContent() != null) ? res.getContent() : "";
            result.put(key, content);
        }
        return result;
    }
}
