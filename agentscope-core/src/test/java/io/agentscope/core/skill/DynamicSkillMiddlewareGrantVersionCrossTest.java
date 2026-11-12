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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.CapabilitySurface;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cross-regression pins for the grant layer ({@code CapabilitySurface}, #3500) meeting the
 * version-contract architecture: the grant vocabulary is the skill <b>name</b>, so one grant
 * entry covers every version of that name — including the version a PIN anchored and versions
 * a mirror cache may still hold. And, symmetrically: with no resolver configured (no surface
 * on the rc), the per-call box, the plain-repository explicit selection and PIN/LATEST
 * binding behave exactly as before the grant layer existed.
 */
@Tag("unit")
@DisplayName("DynamicSkillMiddleware grant × version-contract cross behavior")
class DynamicSkillMiddlewareGrantVersionCrossTest {

    /**
     * Version-capable plain fake: every pull returns a stamped descriptor summary whose body
     * lives in a materialized version directory (the "mirror cache" stand-in). A null version
     * resolves the current latest pointer.
     */
    private static final class VersionedRepo implements AgentSkillRepository {
        private final Map<String, String> latestVersionByName = new LinkedHashMap<>();
        private final Map<String, Map<String, AgentSkill>> contentByVersion = new LinkedHashMap<>();
        private final Path cacheRoot;

        VersionedRepo(Path cacheRoot) {
            this.cacheRoot = cacheRoot;
        }

        void publish(String name, String version, String body) {
            latestVersionByName.put(name, version);
            AgentSkill stored =
                    new AgentSkill(name, "Description of " + name, body, Map.of(), "fake")
                            .toBuilder().version(version).build();
            contentByVersion.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(version, stored);
        }

        @Override
        public AgentSkill getSkill(String name, String version) {
            String resolved =
                    version == null || version.isBlank() ? latestVersionByName.get(name) : version;
            Map<String, AgentSkill> versions = contentByVersion.get(name);
            AgentSkill stored = versions != null ? versions.get(resolved) : null;
            if (stored == null) {
                return null;
            }
            return stored.toBuilder()
                    .clearResources()
                    .skillContent(null)
                    .originDir(materialize(name, resolved))
                    .build();
        }

        private Path materialize(String name, String version) {
            Path dir = cacheRoot.resolve(name).resolve(version);
            try {
                Files.createDirectories(dir);
                Files.writeString(
                        dir.resolve("SKILL.md"),
                        "# " + name + " at " + version,
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return dir;
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.copyOf(latestVersionByName.keySet());
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            throw new UnsupportedOperationException(
                    "serving paths must not enumerate a plain repository");
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return latestVersionByName.containsKey(skillName);
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("fake", "grant-version-cross", false);
        }

        @Override
        public String getSource() {
            return "fake";
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }

    @TempDir Path tempDir;

    private long originalTtl;

    @BeforeEach
    void rememberTtl() {
        originalTtl = DynamicSkillMiddleware.freshnessTtlMillis;
    }

    @AfterEach
    void restoreTtl() {
        DynamicSkillMiddleware.freshnessTtlMillis = originalTtl;
    }

    private static ReActAgent agentWith(AgentSkillRepository repo, SkillVersionPolicy policy) {
        return ReActAgent.builder()
                .name("grant-cross-agent")
                .model(org.mockito.Mockito.mock(Model.class))
                .skillRepository(repo)
                .skillFilter(SkillFilter.only("alpha", "beta"))
                .skillVersionPolicy(policy)
                .build();
    }

    private static RuntimeContext rcOf(String userId, CapabilitySurface surface) {
        RuntimeContext rc = RuntimeContext.builder().userId(userId).sessionId("s").build();
        if (surface != null) {
            rc.put(CapabilitySurface.class, surface);
        }
        return rc;
    }

    /** Loads SKILL.md through the toolkit entry and returns the first output text. */
    private static String load(Toolkit toolkit, String skillId, RuntimeContext ctx) {
        Map<String, Object> input = Map.of("skillId", skillId, "path", "SKILL.md");
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("call-" + skillId)
                        .name("load_skill_through_path")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(use)
                                        .input(input)
                                        .runtimeContext(ctx)
                                        .build())
                        .block();
        List<ContentBlock> outputs = result.getOutput();
        return outputs == null || outputs.isEmpty() ? "" : ((TextBlock) outputs.get(0)).getText();
    }

    @Test
    @DisplayName(
            "grant × PIN routing: a name grant serves the pinned version; denied names stay"
                    + " anchored but invisible and unloadable")
    void grantKeepsGrantedNameAtItsPinnedVersion() {
        VersionedRepo repo = new VersionedRepo(tempDir.resolve("repo-cache"));
        repo.publish("alpha", "v1", "# alpha v1");
        repo.publish("beta", "v1", "# beta v1");
        ReActAgent agent = agentWith(repo, SkillVersionPolicy.pinned());
        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(repo), false);
        Toolkit toolkit = new Toolkit();
        mw.registerSkillTools(toolkit);

        // Arrange: a narrow grant (alpha only) under a PIN policy that anchors at v1.
        RuntimeContext narrow =
                rcOf("u1", new CapabilitySurface(java.util.Set.of("alpha"), null, null));
        mw.onSystemPrompt(agent, narrow, "").block();
        SkillBox box = narrow.get(SkillBox.class);
        assertNotNull(box, "the granted name still produces a per-call box");
        assertNotNull(box.getSkill("alpha_fake"), "the granted name is served");
        assertEquals("v1", box.getSkill("alpha_fake").getVersion(), "at its PIN version");
        assertNull(box.getSkill("beta_fake"), "the denied name never enters the box");

        // A later version of the granted name stays covered by the same name grant — and the
        // pin keeps serving v1 anyway; the denied name remains absent.
        repo.publish("alpha", "v2", "# alpha v2");
        RuntimeContext narrowAgain =
                rcOf("u1", new CapabilitySurface(java.util.Set.of("alpha"), null, null));
        mw.onSystemPrompt(agent, narrowAgain, "").block();
        assertEquals(
                "v1",
                narrowAgain.get(SkillBox.class).getSkill("alpha_fake").getVersion(),
                "one grant entry covers every version; the pin anchors v1 against v2");

        // The denied name's anchor is provenance-only: it survived the deny (a later wide
        // grant serves beta at its PINNED v1, not the version any refresh would move to) —
        // while under the narrow grant its load is rejected with the not-found wording.
        RuntimeContext wide =
                rcOf("u1", new CapabilitySurface(java.util.Set.of("alpha", "beta"), null, null));
        mw.onSystemPrompt(agent, wide, "").block();
        assertEquals(
                "v1",
                wide.get(SkillBox.class).getSkill("beta_fake").getVersion(),
                "the deny neither dropped nor moved the anchor");

        String denied = load(toolkit, "beta_fake", narrowAgain);
        assertEquals(
                "Error: Skill not found: 'beta_fake'. Please check the skill ID.",
                denied,
                "the denied name loads nowhere; the wording is byte-identical to not-found");
        assertTrue(load(toolkit, "alpha_fake", narrowAgain).contains("Successfully"));
    }

    @Test
    @DisplayName(
            "no resolver: per-call box, plain-repo explicit selection and PIN/LATEST behave"
                    + " exactly as before the grant layer")
    void withoutResolverBehaviorMatchesPreGrantBaseline() {
        VersionedRepo repo = new VersionedRepo(tempDir.resolve("repo-cache"));
        repo.publish("alpha", "v1", "# alpha v1");
        repo.publish("beta", "v1", "# beta v1");

        // (a) Direct middleware use without a surface: trusted assembler code — the full
        // visible set enters a per-call box, each call its own instance.
        ReActAgent latestAgent = agentWith(repo, SkillVersionPolicy.latest());
        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(repo), false);
        RuntimeContext callA = rcOf("u-a", null);
        RuntimeContext callB = rcOf("u-a", null);
        mw.onSystemPrompt(latestAgent, callA, "").block();
        mw.onSystemPrompt(latestAgent, callB, "").block();
        SkillBox boxA = callA.get(SkillBox.class);
        SkillBox boxB = callB.get(SkillBox.class);
        assertNotNull(boxA);
        assertNotNull(boxB);
        assertTrue(boxA != boxB, "boxes are per-call, never shared");
        assertNotNull(boxA.getSkill("alpha_fake"));
        assertNotNull(boxA.getSkill("beta_fake"));

        // (b) A plain repository without an explicit selection is skipped (no enumeration,
        // no box) — the pre-grant serving rule, unchanged by the grant merge.
        ReActAgent unfiltered =
                ReActAgent.builder()
                        .name("grant-cross-unfiltered")
                        .model(org.mockito.Mockito.mock(Model.class))
                        .skillRepository(repo)
                        .build();
        RuntimeContext callC = rcOf("u-b", null);
        mw.onSystemPrompt(unfiltered, callC, "").block();
        assertNull(
                callC.get(SkillBox.class),
                "no explicit selection → the plain repository contributes nothing");

        // (c) Version binding without any surface: LATEST advances across calls, PIN freezes.
        DynamicSkillMiddleware.freshnessTtlMillis = 0;
        repo.publish("alpha", "v2", "# alpha v2");
        RuntimeContext latestSecond = rcOf("u-a", null);
        mw.onSystemPrompt(latestAgent, latestSecond, "").block();
        assertEquals(
                "v2",
                latestSecond.get(SkillBox.class).getSkill("alpha_fake").getVersion(),
                "LATEST advances without a surface");

        ReActAgent pinAgent = agentWith(repo, SkillVersionPolicy.pinned());
        RuntimeContext pinFirst = rcOf("u-c", null);
        mw.onSystemPrompt(pinAgent, pinFirst, "").block();
        assertEquals(
                "v2",
                pinFirst.get(SkillBox.class).getSkill("alpha_fake").getVersion(),
                "the pin anchors the current latest at first sight");
        repo.publish("alpha", "v3", "# alpha v3");
        RuntimeContext pinSecond = rcOf("u-c", null);
        mw.onSystemPrompt(pinAgent, pinSecond, "").block();
        assertEquals(
                "v2",
                pinSecond.get(SkillBox.class).getSkill("alpha_fake").getVersion(),
                "PIN freezes against later versions without a surface");
    }
}
