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
package io.agentscope.dataagent.web.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.DataAgentApp;
import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import io.agentscope.dataagent.web.persistence.jpa.ContributionRepository;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.InMemoryStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

/**
 * Locks the {@code PENDING → APPROVED/REJECTED} contract on {@link MarketContributionService} —
 * the surface the admin-approval workflow exposes to operators. Boots the full Spring context on
 * H2 because the service composes JPA persistence with a filesystem materialization step, and the
 * thing we want to catch is the two going out of sync.
 *
 * <p>Each run gets a fresh workspace under {@code java.io.tmpdir}/agentscope-dataagent-contrib-it,
 * so {@code bootstrap.cwd().resolve("shared/agents/<agentId>")} resolves to a clean per-agent slice
 * the test fully owns and can read back to confirm the snapshot landed verbatim.
 */
@Disabled("Pending fix for circular dependency between builderBootstrap and userSandboxRegistry")
@SpringBootTest(classes = DataAgentApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MarketContributionServiceTest.StubModelConfig.class)
@TestPropertySource(
        properties = {
            "dataagent.jwt.secret=test-jwt-secret-must-be-at-least-32-characters-long",
            "dataagent.dashscope.api-key=",
            "spring.datasource.url=jdbc:h2:mem:dataagentContribIT;DB_CLOSE_DELAY=-1;MODE=MYSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.open-in-view=false",
            // Skip the bob/alice demo seed — the contribution rows are what we assert on.
            "spring.sql.init.mode=never"
        })
class MarketContributionServiceTest {

    @Autowired MarketContributionService service;
    @Autowired ContributionRepository repository;
    @Autowired DataAgentBootstrap bootstrap;

    /**
     * Points {@code dataagent.workspace} at a per-run temp dir so the shared root the service
     * writes to is isolated from any other test or developer-machine state.
     */
    @DynamicPropertySource
    static void workspaceDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("agentscope-dataagent-contrib-it");
        registry.add("dataagent.workspace", dir::toString);
    }

    private static List<FileEntry> body(String content) {
        return List.of(new FileEntry("", content));
    }

    @Test
    void submitInsertsPendingRow() {
        ContributionEntity submitted =
                service.submit(
                        "alice",
                        "data-agent",
                        null,
                        ContributionEntity.TARGET_SKILL,
                        "cohort-builder",
                        "useful for quarterly cohort analysis",
                        body("# Cohort Builder\n\nbuild cohorts.\n"));

        assertThat(submitted.getId()).isNotNull();
        assertThat(submitted.getStatus()).isEqualTo(ContributionEntity.STATUS_PENDING);
        assertThat(submitted.getSourceUserId()).isEqualTo("alice");
        assertThat(submitted.getTargetAgentId()).isEqualTo("data-agent");
        assertThat(submitted.getReviewerUserId()).isNull();

        // Visible through both query paths.
        assertThat(service.listByStatus(ContributionEntity.STATUS_PENDING))
                .extracting(ContributionEntity::getId)
                .contains(submitted.getId());
        assertThat(service.listMine("alice"))
                .extracting(ContributionEntity::getId)
                .containsExactly(submitted.getId());
    }

    /**
     * Approving a bare skill name materializes {@code SKILL.md} inside the skill bundle directory
     * under the per-agent slice — the convention the sandbox projection expects.
     */
    @Test
    void approveSkillMaterializesSkillMdUnderSharedRoot() throws IOException {
        String text = "# Demo Skill\n\nhello.\n";
        ContributionEntity submitted =
                service.submit(
                        "alice",
                        "data-agent",
                        null,
                        ContributionEntity.TARGET_SKILL,
                        "demo-skill",
                        "demo rationale",
                        body(text));

        ContributionEntity approved =
                service.approve(submitted.getId(), "admin", "looks good", null);

        assertThat(approved.getStatus()).isEqualTo(ContributionEntity.STATUS_APPROVED);
        assertThat(approved.getReviewerUserId()).isEqualTo("admin");
        assertThat(approved.getReviewerNote()).isEqualTo("looks good");
        assertThat(approved.getUpdatedAt()).isGreaterThanOrEqualTo(submitted.getCreatedAt());

        Path expected =
                bootstrap
                        .cwd()
                        .resolve("shared")
                        .resolve("agents")
                        .resolve("data-agent")
                        .resolve("skills")
                        .resolve("demo-skill")
                        .resolve("SKILL.md");
        assertThat(expected).exists();
        assertThat(Files.readString(expected, StandardCharsets.UTF_8)).isEqualTo(text);
    }

    /**
     * Subagents and memory snippets land at the literal target path under their per-agent type
     * directory, not the SKILL.md special-case.
     */
    @Test
    void approveSubagentMaterializesAtLiteralPath() throws IOException {
        String text = "# Report Writer\n\nplain markdown body.\n";
        ContributionEntity submitted =
                service.submit(
                        "alice",
                        "data-agent",
                        null,
                        ContributionEntity.TARGET_SUBAGENT,
                        "report-writer.md",
                        null,
                        body(text));

        service.approve(submitted.getId(), "admin", null, null);

        Path expected =
                bootstrap
                        .cwd()
                        .resolve("shared")
                        .resolve("agents")
                        .resolve("data-agent")
                        .resolve("subagents")
                        .resolve("report-writer.md");
        assertThat(expected).exists();
        assertThat(Files.readString(expected, StandardCharsets.UTF_8)).isEqualTo(text);
    }

    @Test
    void rejectTransitionsWithoutFilesystemWrite() {
        ContributionEntity submitted =
                service.submit(
                        "alice",
                        "data-agent",
                        null,
                        ContributionEntity.TARGET_MEMORY,
                        "rejected-snippet.md",
                        null,
                        body("memory body"));

        ContributionEntity rejected =
                service.reject(submitted.getId(), "admin", "duplicate of existing entry");

        assertThat(rejected.getStatus()).isEqualTo(ContributionEntity.STATUS_REJECTED);
        assertThat(rejected.getReviewerNote()).isEqualTo("duplicate of existing entry");

        Path memoryDir =
                bootstrap
                        .cwd()
                        .resolve("shared")
                        .resolve("agents")
                        .resolve("data-agent")
                        .resolve("memory");
        // Reject must leave the per-agent memory tree alone — no file with the rejected path is
        // present even though the directory may have been created by a prior approval.
        assertThat(memoryDir.resolve("rejected-snippet.md")).doesNotExist();
    }

    /**
     * Once approved, a contribution may not be re-approved or rejected — both must throw and the
     * persisted row stays at {@code APPROVED}.
     */
    @Test
    void terminalStatusesAreNotRetransitionable() {
        ContributionEntity submitted =
                service.submit(
                        "alice",
                        "data-agent",
                        null,
                        ContributionEntity.TARGET_SKILL,
                        "idempotent-skill",
                        null,
                        body("# Idempotent\n"));

        service.approve(submitted.getId(), "admin", "first approval", null);

        assertThatThrownBy(
                        () -> service.approve(submitted.getId(), "admin", "second approval", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not pending");
        assertThatThrownBy(() -> service.reject(submitted.getId(), "admin", "too late"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not pending");

        ContributionEntity reloaded = repository.findById(submitted.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ContributionEntity.STATUS_APPROVED);
        assertThat(reloaded.getReviewerNote()).isEqualTo("first approval");
    }

    @Test
    void approveOnMissingIdThrows() {
        assertThatThrownBy(() -> service.approve(999_999L, "admin", "ghost", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void submitRejectsInvalidInputBeforePersisting() {
        long before = repository.count();

        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "",
                                        "agent",
                                        null,
                                        ContributionEntity.TARGET_SKILL,
                                        "p",
                                        null,
                                        body("body")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "alice",
                                        "agent",
                                        null,
                                        "bogus-type",
                                        "p",
                                        null,
                                        body("body")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "alice",
                                        "agent",
                                        null,
                                        ContributionEntity.TARGET_SKILL,
                                        "",
                                        null,
                                        body("body")))
                .isInstanceOf(IllegalArgumentException.class);
        // Path traversal is rejected at submit time — the file-write guard is defence in depth.
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "alice",
                                        "agent",
                                        null,
                                        ContributionEntity.TARGET_SKILL,
                                        "../escape",
                                        null,
                                        body("body")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "alice",
                                        "agent",
                                        null,
                                        ContributionEntity.TARGET_SKILL,
                                        "p",
                                        null,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.count())
                .as("no rows must be inserted when validation rejects the submission")
                .isEqualTo(before);
    }

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        @Primary
        Model stubModel() {
            Model model = Mockito.mock(Model.class);
            Mockito.when(model.getModelName()).thenReturn("stub-model");
            ChatResponse chunk =
                    new ChatResponse(
                            "stub-id",
                            List.of(TextBlock.builder().text("ok").build()),
                            null,
                            Map.of(),
                            "stop");
            Mockito.when(model.stream(Mockito.anyList(), Mockito.any(), Mockito.any()))
                    .thenReturn(Flux.just(chunk));
            return model;
        }

        @Bean
        BaseStore inMemoryStore() {
            return new InMemoryStore();
        }
    }
}
