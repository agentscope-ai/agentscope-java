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
package io.agentscope.harness.agent.skill.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of the skills visible to a single {@code onSystemPrompt} pass.
 *
 * <p>Keyed by {@code skillId} (the AgentSkill's {@code name + "_" + source}), insertion-order
 * preserved so the prompt renders skills in compose order.
 */
public final class SkillCatalog {

    private final Map<String, HarnessSkillEntry> entries;

    private SkillCatalog(Map<String, HarnessSkillEntry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public static SkillCatalog empty() {
        return new SkillCatalog(Collections.emptyMap());
    }

    public static SkillCatalog of(List<HarnessSkillEntry> orderedEntries) {
        LinkedHashMap<String, HarnessSkillEntry> map = new LinkedHashMap<>();
        for (HarnessSkillEntry e : orderedEntries) {
            if (e == null) {
                continue;
            }
            map.put(e.skill().getSkillId(), e);
        }
        return new SkillCatalog(map);
    }

    /** Lookup by {@code skillId} (name_source). Returns {@code null} when absent. */
    public HarnessSkillEntry get(String skillId) {
        return skillId == null ? null : entries.get(skillId);
    }

    /** All entries in compose order (low-to-high priority). */
    public Collection<HarnessSkillEntry> all() {
        return entries.values();
    }

    /** All skillIds, in compose order. */
    public List<String> ids() {
        return List.copyOf(entries.keySet());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }
}
