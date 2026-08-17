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
package io.agentscope.harness.agent.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for {@link SubagentSpecGenerator}. Uses a tiny in-process {@link Model} stub that
 * returns canned markdown without touching any real provider — keeps the test deterministic.
 */
class SubagentSpecGeneratorTest {

    private static Model fixedModel(String markdown) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                ChatResponse resp =
                        ChatResponse.builder()
                                .content(
                                        List.<ContentBlock>of(
                                                TextBlock.builder().text(markdown).build()))
                                .build();
                return Flux.just(resp);
            }

            @Override
            public String getModelName() {
                return "fixture";
            }
        };
    }

    @Test
    void generateAndValidate_returnsParsedDeclaration() {
        String spec =
                """
                ---
                description: Reviews code for security and concurrency issues.
                mode: subagent
                hidden: false
                temperature: 0.2
                steps: 8
                ---
                You are a code reviewer. Focus on security and concurrency. Return findings as a
                bullet list.
                """;
        SubagentSpecGenerator gen = new SubagentSpecGenerator(fixedModel(spec));

        SubagentSpecGenerator.GeneratedSpec result =
                gen.generateAndValidate("review code", "code-reviewer", Set.of(), null).block();

        assertNotNull(result);
        SubagentDeclaration decl = result.declaration();
        assertEquals("code-reviewer", decl.getName());
        assertEquals("Reviews code for security and concurrency issues.", decl.getDescription());
        assertEquals(SubagentDeclaration.Mode.SUBAGENT, decl.getMode());
        assertEquals(0.2, decl.getTemperature());
        assertEquals(8, decl.getSteps());
        assertTrue(result.markdown().contains("You are a code reviewer"));
    }

    @Test
    void generateAndValidate_malformedFrontmatter_throws() {
        String spec = "no frontmatter at all";
        SubagentSpecGenerator gen = new SubagentSpecGenerator(fixedModel(spec));
        // Mono error surfaces as RuntimeException at .block()
        assertThrows(
                IllegalStateException.class,
                () -> gen.generateAndValidate("x", "x", Set.of(), null).block());
    }

    @Test
    void generateMarkdown_concatenatesTextBlocks() {
        Model two =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        ChatResponse a =
                                ChatResponse.builder()
                                        .content(
                                                List.<ContentBlock>of(
                                                        TextBlock.builder().text("partA").build()))
                                        .build();
                        ChatResponse b =
                                ChatResponse.builder()
                                        .content(
                                                List.<ContentBlock>of(
                                                        TextBlock.builder().text("partB").build()))
                                        .build();
                        return Flux.just(a, b);
                    }

                    @Override
                    public String getModelName() {
                        return "fixture-2";
                    }
                };
        SubagentSpecGenerator gen = new SubagentSpecGenerator(two);
        String md = gen.generateMarkdown("desc", Set.of("foo", "bar")).block();
        assertEquals("partApartB", md);
    }
}
