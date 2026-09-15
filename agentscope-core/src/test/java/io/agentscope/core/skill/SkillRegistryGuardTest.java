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

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the unknown-skill guard of the registry accessor used by the SKILL.md dedup (#1569):
 * an id with no registration must yield a safe default and must not throw.
 */
class SkillRegistryGuardTest {

    @Test
    @DisplayName("Unknown skill IDs return safe defaults from every registry accessor")
    void unknownSkillGuardsReturnSafeDefaults() {
        SkillRegistry registry = new SkillRegistry();

        assertFalse(registry.isSkillActive("never_registered"), "unknown id is not active");

        // Both mutators tolerate an unknown id instead of throwing.
        registry.setSkillActive("never_registered", false);
        registry.setAllSkillsActive(false);

        assertFalse(registry.isSkillActive("never_registered"));
    }
}
