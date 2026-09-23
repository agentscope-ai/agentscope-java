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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link ReActAgent.Builder#build()} does not hijack the builder's {@link SkillBox}.
 *
 * <p>{@code configureSkillBox} used to call {@code bindToolkit} on the builder's own box, which
 * repointed it — and therefore every previously built agent's {@code SkillHook} — at the newest
 * agent's toolkit. Building now takes a per-agent copy instead. See issue #979.
 */
@DisplayName("ReActAgent SkillBox isolation")
class ReActAgentSkillBoxIsolationTest {

    private static final String SKILL_TOOL_GROUP = "skill-build-in-tools";

    private static ReActAgent.Builder builderWith(SkillBox box) {
        return ReActAgent.builder()
                .name("skillbox-agent")
                .sysPrompt("test")
                .model(new MockModel("ok"))
                .skillBox(box);
    }

    private static SkillBox boxOn(Toolkit toolkit) {
        SkillBox box = new SkillBox(toolkit);
        box.setAutoUploadSkill(false); // keep the test off the filesystem
        box.registerSkill(new AgentSkill("demo_skill", "demo", "# Content", null));
        return box;
    }

    @Test
    @DisplayName("build() leaves the builder's SkillBox bound to its original toolkit")
    void buildDoesNotRebindTheBuildersSkillBox() {
        Toolkit ownToolkit = new Toolkit();
        SkillBox box = boxOn(ownToolkit);

        ReActAgent agent = builderWith(box).build();
        assertNotNull(agent);

        // If build() had rebound the shared box to the agent's toolkit copy, this would register
        // the skill-load tool over there instead, and ownToolkit would never see the group.
        box.registerSkillLoadTool();
        assertNotNull(
                ownToolkit.getToolGroup(SKILL_TOOL_GROUP),
                "builder's SkillBox must still serve the toolkit it was created with");
    }

    @Test
    @DisplayName("two agents from one builder do not share skill state")
    void repeatedBuildsKeepSkillStateIsolated() {
        Toolkit ownToolkit = new Toolkit();
        SkillBox box = boxOn(ownToolkit);

        ReActAgent.Builder builder = builderWith(box);
        ReActAgent first = builder.build();
        ReActAgent second = builder.build();

        assertNotSame(first, second, "each build() must produce a distinct agent");

        // Still bound to its own toolkit after two builds, not to either agent's copy.
        box.registerSkillLoadTool();
        assertNotNull(
                ownToolkit.getToolGroup(SKILL_TOOL_GROUP),
                "a second build() must not repoint the builder's SkillBox either");
    }
}
