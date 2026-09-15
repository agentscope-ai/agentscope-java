/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SkillBox#copy(Toolkit)}.
 *
 * <p>A SkillBox holds a mutable reference to the toolkit it serves, so one instance cannot back
 * two agents. {@code copy} gives each agent its own box while sharing the (immutable) skills.
 */
@DisplayName("SkillBox copy")
class SkillBoxCopyTest {

    private static AgentSkill skill(String id) {
        return new AgentSkill(id, "desc for " + id, "# Content", null);
    }

    @Test
    @DisplayName("copy carries registered skills without sharing the registry")
    void copyCarriesSkillsIndependently() {
        Toolkit original = new Toolkit();
        SkillBox box = new SkillBox(original);
        AgentSkill alpha = skill("alpha");
        box.registerSkill(alpha);

        SkillBox copy = box.copy(new Toolkit());

        assertNotSame(box, copy, "copy must be a distinct instance");
        assertTrue(
                copy.exists(alpha.getSkillId()),
                "copy should carry skills registered before the copy");

        // Registrations after the copy must not bleed in either direction.
        AgentSkill lateOnOriginal = skill("late-original");
        AgentSkill lateOnCopy = skill("late-copy");
        box.registerSkill(lateOnOriginal);
        copy.registerSkill(lateOnCopy);

        assertFalse(
                copy.exists(lateOnOriginal.getSkillId()),
                "copy must not see skills registered on the original after the copy");
        assertFalse(
                box.exists(lateOnCopy.getSkillId()),
                "original must not see skills registered on the copy");
    }

    @Test
    @DisplayName("copy leaves the source box bound to its own toolkit")
    void copyDoesNotRebindSource() {
        Toolkit sourceToolkit = new Toolkit();
        Toolkit agentToolkit = new Toolkit();
        SkillBox box = new SkillBox(sourceToolkit);
        box.registerSkill(skill("alpha"));

        SkillBox copy = box.copy(agentToolkit);
        copy.registerSkillLoadTool();

        // The load tool belongs to the copy's toolkit only. Before per-agent copying, the shared
        // box was rebound to the agent's toolkit, so the source lost its binding entirely.
        assertTrue(
                agentToolkit.getToolNames().stream().anyMatch(n -> n.contains("skill")),
                "copy's toolkit should have received the skill-load tool, got "
                        + agentToolkit.getToolNames());
        assertTrue(
                sourceToolkit.getToolNames().stream().noneMatch(n -> n.contains("skill")),
                "source toolkit must be untouched by the copy, got "
                        + sourceToolkit.getToolNames());
    }

    @Test
    @DisplayName("copy carries configuration flags")
    void copyCarriesConfiguration() {
        SkillBox box = new SkillBox(new Toolkit());
        box.setAutoUploadSkill(false);

        SkillBox copy = box.copy(new Toolkit());

        assertEquals(
                box.isAutoUploadSkill(),
                copy.isAutoUploadSkill(),
                "autoUploadSkill must carry over to the copy");
    }

    @Test
    @DisplayName("copy rejects a null toolkit")
    void copyRejectsNullToolkit() {
        SkillBox box = new SkillBox(new Toolkit());
        assertThrows(IllegalArgumentException.class, () -> box.copy(null));
    }
}
