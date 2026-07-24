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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Regression tests for <a
 * href="https://github.com/agentscope-ai/agentscope-java/issues/2169">#2169</a>.
 *
 * <p>Bug: on the first call of a fresh session, {@code activateSlotForContext} applied the
 * state's (empty) activated group list via {@code setActiveGroups}, deactivating the build-time
 * active {@code skill-build-in-tools} group. {@code SkillHook} kept advertising skills in the
 * system prompt, so the model inevitably called {@code load_skill_through_path} and received
 * {@code Unauthorized tool call: 'load_skill_through_path' is not available}. Fixed on main by
 * seeding fresh-session tool context from the build-time active groups; these tests pin the
 * end-to-end behavior through a real {@link SkillBox} + {@link ReActAgent} call.
 */
@DisplayName("#2169: skill load tool on fresh sessions")
class SkillLoadToolFreshSessionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String SKILL_LOAD_TOOL = "load_skill_through_path";
    private static final String SKILL_GROUP = "skill-build-in-tools";
    private static final String SKILL_ID = "demo_skill";
    private static final String SKILL_BODY = "# Demo Skill Body";

    /**
     * First turn emits a {@code load_skill_through_path} call; on the second turn it records the
     * tool result exactly as the model would see it, then answers with plain text to end the
     * loop.
     */
    private static final class ScriptModel extends ChatModelBase {
        private final AtomicInteger turns = new AtomicInteger();
        private volatile String observedToolResult;

        void reset() {
            turns.set(0);
            observedToolResult = null;
        }

        @Override
        public String getModelName() {
            return "script";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (turns.getAndIncrement() == 0) {
                // Registered skill ids carry a "_custom" source suffix (see RegisteredSkill);
                // the load tool's skillId enum only accepts the registered form.
                String registeredSkillId = SKILL_ID + "_custom";
                Map<String, Object> input = new HashMap<>();
                input.put("skillId", registeredSkillId);
                input.put("path", "SKILL.md");
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(
                                                ToolUseBlock.builder()
                                                        .id("call-1")
                                                        .name(SKILL_LOAD_TOOL)
                                                        .input(input)
                                                        // Real model responses carry the raw
                                                        // arguments JSON here; ToolValidator
                                                        // validates this field, not the map.
                                                        .content(
                                                                "{\"skillId\":\""
                                                                        + registeredSkillId
                                                                        + "\",\"path\":\"SKILL.md\"}")
                                                        .build()))
                                .build());
            }
            this.observedToolResult =
                    messages.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .flatMap(r -> r.getOutput().stream())
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .collect(Collectors.joining("\n"));
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.<ContentBlock>of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static RuntimeContext session(String sessionId) {
        return RuntimeContext.builder().sessionId(sessionId).build();
    }

    private static ReActAgent buildAgent(ScriptModel model) {
        Toolkit toolkit = new Toolkit();
        SkillBox skillBox = new SkillBox(toolkit);
        skillBox.registerSkill(new AgentSkill(SKILL_ID, "Demo Skill", SKILL_BODY, new HashMap<>()));
        return ReActAgent.builder()
                .name("asst")
                .sysPrompt("You load skills when asked.")
                .model(model)
                .toolkit(toolkit)
                .skillBox(skillBox)
                // Unattended execution: bypass the permission engine so the scripted tool call
                // runs without an interactive confirmation channel.
                .permissionContext(
                        PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .build();
    }

    @Test
    @DisplayName("fresh session: load_skill_through_path executes instead of Unauthorized")
    void freshSession_skillLoadToolExecutes() {
        ScriptModel model = new ScriptModel();
        ReActAgent agent = buildAgent(model);

        Msg reply =
                agent.call(List.of(userMsg("load the demo skill")), session("fresh-1"))
                        .block(TIMEOUT);
        assertNotNull(reply);
        assertNotNull(
                model.observedToolResult,
                "scripted model never observed a tool result — skill load tool did not run");

        assertFalse(
                model.observedToolResult.contains("Unauthorized tool call"),
                "#2169 regression: fresh session rejected the skill load tool: "
                        + model.observedToolResult);
        assertTrue(
                model.observedToolResult.contains(SKILL_BODY),
                "skill content not returned by load tool: " + model.observedToolResult);
    }

    @Test
    @DisplayName("fresh session: state seeds skill-build-in-tools as active")
    void freshSession_stateSeedsSkillGroup() {
        ScriptModel model = new ScriptModel();
        ReActAgent agent = buildAgent(model);

        agent.call(List.of(userMsg("hi")), session("fresh-2")).block(TIMEOUT);

        List<String> activeGroups =
                agent.getAgentState(null, "fresh-2").getToolContext().getActivatedGroups();
        assertTrue(
                activeGroups.contains(SKILL_GROUP),
                "fresh session state must keep the build-time active skill group, got: "
                        + activeGroups);
    }

    @Test
    @DisplayName("second call in same session: skill load tool still authorized")
    void secondCall_skillLoadToolStillAuthorized() {
        ScriptModel model = new ScriptModel();
        ReActAgent agent = buildAgent(model);

        agent.call(List.of(userMsg("first")), session("reuse-1")).block(TIMEOUT);
        String firstResult = model.observedToolResult;
        assertNotNull(firstResult);
        assertFalse(firstResult.contains("Unauthorized tool call"), firstResult);

        model.reset();
        agent.call(List.of(userMsg("second")), session("reuse-1")).block(TIMEOUT);

        assertNotNull(
                model.observedToolResult,
                "second call: skill load tool did not run (group lost activation again)");
        assertFalse(
                model.observedToolResult.contains("Unauthorized tool call"),
                "#2169 regression on second call: " + model.observedToolResult);
    }
}
