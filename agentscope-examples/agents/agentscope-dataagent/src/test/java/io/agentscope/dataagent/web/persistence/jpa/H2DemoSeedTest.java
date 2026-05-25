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
package io.agentscope.dataagent.web.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.web.DataAgentApp;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.InMemoryStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * Verifies that {@code data-h2.sql} executes on top of a <strong>file-mode</strong> H2 DataSource
 * — exactly the production default — and seeds the {@code bob} and {@code alice} demo accounts
 * with passwords identical to the usernames.
 *
 * <p>The file-mode coverage matters because Spring Boot 4's {@code spring.sql.init.mode=embedded}
 * only classifies {@code jdbc:h2:mem:} as embedded
 * (<a href="https://github.com/spring-projects/spring-boot/issues/32865">spring-boot#32865</a>);
 * an in-memory test alone would not catch the regression where file-mode H2 silently skips the
 * seed. This test also locks in:
 *
 * <ul>
 *   <li>BCrypt hash constants in {@code data-h2.sql} verify against the documented plaintext
 *       passwords ({@code bob} / {@code alice}).
 *   <li>{@code spring.sql.init.mode=always} (our default) makes the seed run for file URLs.
 *   <li>The {@code @PostConstruct} admin seed is order-safe and still inserts {@code admin}
 *       even when the SQL initializer has already populated other rows.
 * </ul>
 */
@SpringBootTest(classes = DataAgentApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(H2DemoSeedTest.StubModelConfig.class)
@TestPropertySource(
        properties = {
            "dataagent.jwt.secret=test-jwt-secret-must-be-at-least-32-characters-long",
            "dataagent.workspace=${java.io.tmpdir}/agentscope-dataagent-h2-seed-test",
            "dataagent.dashscope.api-key=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.defer-datasource-initialization=true",
        })
class H2DemoSeedTest {

    @Autowired UserStore userStore;

    @DynamicPropertySource
    static void h2FileDataSource(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("agentscope-dataagent-h2-seed-test-db");
        String dbPath = dir.resolve("db").toString();
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:file:" + dbPath + ";MODE=MYSQL;DB_CLOSE_DELAY=-1");
    }

    @Test
    void bobAndAliceSeededWithMatchingPasswords() {
        UserStore.UserRecord bob =
                userStore
                        .findByUsername("bob")
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "bob was not seeded — check data-h2.sql or"
                                                        + " spring.sql.init wiring"));
        UserStore.UserRecord alice =
                userStore
                        .findByUsername("alice")
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "alice was not seeded — check data-h2.sql"));

        assertThat(bob.roles()).containsExactly("user");
        assertThat(alice.roles()).containsExactly("user");

        assertThat(userStore.verifyPassword(bob, "bob"))
                .as("bob's seeded password hash must verify against the plaintext 'bob'")
                .isTrue();
        assertThat(userStore.verifyPassword(alice, "alice"))
                .as("alice's seeded password hash must verify against the plaintext 'alice'")
                .isTrue();

        assertThat(userStore.verifyPassword(bob, "alice")).isFalse();

        UserStore.UserRecord admin =
                userStore
                        .findById("admin")
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "admin was not seeded —"
                                                    + " JpaUserStore.seedDefaultAdmin must remain"
                                                    + " order-safe with data-h2.sql"));
        assertThat(admin.roles()).contains("admin");
        assertThat(userStore.verifyPassword(admin, "admin")).isTrue();
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
