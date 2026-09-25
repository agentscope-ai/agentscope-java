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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * End-to-end coverage for skill staging when several callers share one workspace root.
 *
 * <p>Reproduces the shape reported against 2.0.1: concurrent calls whose visible skill sets
 * differ used to make one caller's orphan GC delete the directory another caller had just
 * staged — aborting that call with an {@code UncheckedIOException} out of
 * {@code HarnessSkillMiddleware.onSystemPrompt}, before the first model round.
 *
 * <p>Both tests drive the real production path (middleware → {@link MarketplaceStager} → disk);
 * the model is a stub because the failure happens strictly before any model call.
 */
class SharedWorkspaceSkillStagingE2ETest {

    // [^<]* on purpose: the prompt's <usage> legend also mentions the <files-root> tag, and a
    // DOTALL wildcard would splice that mention onto the first real closing tag.
    private static final Pattern FILES_ROOT = Pattern.compile("<files-root>([^<]*)</files-root>");

    @Test
    @DisplayName("E2E: two agents sharing one workspace both complete their calls")
    void concurrentAgentsSharingWorkspaceBothComplete(@TempDir Path shared) throws Exception {
        // Agent A sees both skills, agent B sees only one — B's retain-list makes "beta" an
        // orphan from B's point of view while A is still staging it. Both agents are closed
        // afterwards: a HarnessAgent owns maintenance threads, and leaking them into the shared
        // surefire JVM starves later timing-sensitive tests.
        try (HarnessAgent agentA = agent("agent-a", shared, repo("src-a", "alpha", "beta"));
                HarnessAgent agentB = agent("agent-b", shared, repo("src-b", "alpha"))) {

            List<Throwable> errors = runConcurrently(50, agentA, agentB);

            assertEquals(List.of(), errors, () -> "no call should fail, but got: " + errors);
            assertTrue(
                    Files.isDirectory(shared.resolve(MarketplaceStager.CACHE_DIR)),
                    "the shared cache should exist after the calls");
        }
    }

    @Test
    @DisplayName("E2E: per-user visibility — the prompt never points at a deleted directory")
    void perUserVisibilityKeepsEachUsersFilesOnDisk(@TempDir Path shared) throws Exception {
        HarnessSkillMiddleware middleware = perUserMiddleware(shared);

        List<Throwable> errors = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        AtomicInteger advertisedCount = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        for (String user : List.of("alice", "bob")) {
            Thread t =
                    new Thread(
                            () -> {
                                RuntimeContext ctx =
                                        RuntimeContext.builder()
                                                .sessionId("s-" + user)
                                                .userId(user)
                                                .build();
                                try {
                                    start.await();
                                    for (int i = 0; i < 60; i++) {
                                        String prompt =
                                                middleware.onSystemPrompt(null, ctx, "").block();
                                        // Checked HERE, not after the run: the point is that the
                                        // path handed to the model is valid at the moment it is
                                        // handed over. Checking at the end would only observe
                                        // whichever caller happened to stage last.
                                        assertNotNull(prompt);
                                        Matcher m = FILES_ROOT.matcher(prompt);
                                        int seen = 0;
                                        while (m.find()) {
                                            Path advertised =
                                                    Paths.get(
                                                            m.group(1).trim().replace("\\ ", " "));
                                            if (!Files.isDirectory(advertised)) {
                                                synchronized (missing) {
                                                    missing.add(advertised.toString());
                                                }
                                            }
                                            seen++;
                                        }
                                        advertisedCount.addAndGet(seen);
                                    }
                                } catch (Throwable e) {
                                    synchronized (errors) {
                                        errors.add(e);
                                    }
                                } finally {
                                    done.countDown();
                                }
                            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "both users should finish");

        assertEquals(List.of(), errors, () -> "onSystemPrompt must not fail, but got: " + errors);
        assertEquals(
                List.of(),
                missing,
                () -> "prompt advertised files-roots that were not on disk: " + missing);
        assertTrue(advertisedCount.get() > 0, "fixture should have advertised a files-root");
    }

    @Test
    @DisplayName("E2E: one user's call must not delete a skill staged for another user")
    void oneUsersCallDoesNotDeleteAnotherUsersSkill(@TempDir Path shared) {
        HarnessSkillMiddleware middleware = perUserMiddleware(shared);
        RuntimeContext bob = RuntimeContext.builder().sessionId("s-bob").userId("bob").build();
        RuntimeContext alice =
                RuntimeContext.builder().sessionId("s-alice").userId("alice").build();

        // Bob can see "beta", so his call stages it and his prompt points the model at it.
        String bobPrompt = middleware.onSystemPrompt(null, bob, "").block();
        assertNotNull(bobPrompt);
        assertTrue(bobPrompt.contains("<name>beta</name>"), "bob should see beta");
        // Scoped by userId: bob's subtree is one alice's call cannot reach at all, which is
        // what turns "must not delete" from a timing property into a structural one.
        Path betaDir =
                shared.resolve(MarketplaceStager.CACHE_DIR)
                        .resolve("bob")
                        .resolve("market")
                        .resolve("beta");
        assertTrue(Files.isDirectory(betaDir), "beta should be staged for bob");

        // Alice cannot see "beta". Her orphan GC must not reclaim what bob is still using.
        middleware.onSystemPrompt(null, alice, "").block();

        assertTrue(
                Files.isDirectory(betaDir),
                "alice's call deleted the directory bob's prompt still points at: " + betaDir);
    }

    // =========================================================================
    //  Fixtures
    // =========================================================================

    /** "beta" is visible to bob only; "alpha" to everyone — the report's per-user setup. */
    private static HarnessSkillMiddleware perUserMiddleware(Path workspace) {
        SkillVisibilityFilter perUser =
                (all, ctx) ->
                        all.stream()
                                .filter(
                                        s ->
                                                !"beta".equals(s.getName())
                                                        || "bob".equals(ctx.getUserId()))
                                .toList();
        return new HarnessSkillMiddleware(
                List.of(repo("market", "alpha", "beta")),
                new Toolkit(),
                null,
                perUser,
                new MarketplaceStager(workspace),
                ShellPathPolicy.localWithShell(workspace));
    }

    private static List<Throwable> runConcurrently(int rounds, HarnessAgent... agents)
            throws InterruptedException {
        List<Throwable> errors = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(agents.length);
        for (int i = 0; i < agents.length; i++) {
            HarnessAgent agent = agents[i];
            String user = "user-" + i;
            Thread t =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    for (int r = 0; r < rounds; r++) {
                                        RuntimeContext ctx =
                                                RuntimeContext.builder()
                                                        .sessionId(user + "-" + r)
                                                        .userId(user)
                                                        .build();
                                        agent.call(new UserMessage("hi"), ctx).block();
                                    }
                                } catch (Throwable e) {
                                    synchronized (errors) {
                                        errors.add(e);
                                    }
                                } finally {
                                    done.countDown();
                                }
                            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "agent calls should finish");
        return errors;
    }

    private static HarnessAgent agent(String name, Path workspace, AgentSkillRepository repo) {
        return HarnessAgent.builder()
                .name(name)
                .model(new StubModel())
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .skillRepository(repo)
                .build();
    }

    /** Skills carry a subdirectory — the shape whose traversal used to abort. */
    private static AgentSkillRepository repo(String source, String... names) {
        List<AgentSkill> skills = new ArrayList<>();
        for (String name : names) {
            Map<String, String> resources = new LinkedHashMap<>();
            resources.put("SKILL.md", "# " + name + "\n");
            for (int i = 0; i < 6; i++) {
                resources.put("scripts/run" + i + ".sh", "#!/bin/sh\necho " + name + "\n");
            }
            skills.add(new AgentSkill(name, "desc", "# " + name + "\n", resources, source));
        }
        return new StubRepo(skills, source);
    }

    private static final class StubModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "stub-model";
        }
    }

    private static final class StubRepo implements AgentSkillRepository {

        private final List<AgentSkill> skills;
        private final String source;

        StubRepo(List<AgentSkill> skills, String source) {
            this.skills = skills;
            this.source = source;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
        }

        @Override
        public List<String> getAllSkillNames() {
            return skills.stream().map(AgentSkill::getName).toList();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return skills;
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
            return skills.stream().anyMatch(s -> s.getName().equals(skillName));
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo(source, "", false);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }
}
