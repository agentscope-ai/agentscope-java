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
package io.agentscope.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class BashTest {

    private Bash bash;
    private PermissionContext defaultCtx;

    @BeforeEach
    void setUp() {
        bash = new Bash();
        defaultCtx = PermissionContext.builder().mode(PermissionMode.DEFAULT).build();
    }

    private PermissionDecision decide(String command) {
        return bash.checkPermissions(Map.of("command", command), defaultCtx).block();
    }

    private PermissionDecision decide(String command, PermissionContext ctx) {
        return bash.checkPermissions(Map.of("command", command), ctx).block();
    }

    @Nested
    class CheckPermissions {

        @Test
        void missingCommandIsPassthrough() {
            PermissionDecision d = bash.checkPermissions(Map.of(), defaultCtx).block();
            assertNotNull(d);
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }

        @Test
        void readOnlyCommandIsAllowed() {
            PermissionDecision d = decide("ls -la");
            assertEquals(PermissionBehavior.ALLOW, d.getBehavior());
        }

        @Test
        void gitStatusIsAllowed() {
            PermissionDecision d = decide("git status");
            assertEquals(PermissionBehavior.ALLOW, d.getBehavior());
        }

        @Test
        void injectionRiskAsksWithSafetyReason() {
            PermissionDecision d = decide("rm $(cat list.txt)");
            assertEquals(PermissionBehavior.ASK, d.getBehavior());
            assertTrue(d.getDecisionReason().toLowerCase(Locale.ROOT).contains("safety"));
        }

        @Test
        void dangerousPatternAsks() {
            PermissionDecision d = decide("rm -rf /");
            assertEquals(PermissionBehavior.ASK, d.getBehavior());
            assertTrue(d.getDecisionReason().toLowerCase(Locale.ROOT).contains("safety"));
        }

        @Test
        void sedInPlaceOnDangerousFileAsks() {
            PermissionDecision d = decide("sed -i 's/x/y/' .bashrc");
            assertEquals(PermissionBehavior.ASK, d.getBehavior());
            assertTrue(d.getDecisionReason().toLowerCase(Locale.ROOT).contains("safety"));
        }

        @Test
        void dangerousPathInExtractedFilesAsks() {
            PermissionDecision d = decide("cp credentials .bashrc");
            assertEquals(PermissionBehavior.ASK, d.getBehavior());
            assertTrue(d.getDecisionReason().toLowerCase(Locale.ROOT).contains("safety"));
        }

        @Test
        void criticalRemovalAsks() {
            PermissionDecision d = decide("rm -rf /etc");
            assertEquals(PermissionBehavior.ASK, d.getBehavior());
            assertTrue(d.getDecisionReason().toLowerCase(Locale.ROOT).contains("safety"));
        }

        @Test
        void acceptEditsAllowsFilesystemCommand() {
            PermissionContext ctx =
                    PermissionContext.builder().mode(PermissionMode.ACCEPT_EDITS).build();
            PermissionDecision d = decide("mkdir new_dir", ctx);
            assertEquals(PermissionBehavior.ALLOW, d.getBehavior());
        }

        @Test
        void acceptEditsDoesNotAllowArbitraryCommand() {
            PermissionContext ctx =
                    PermissionContext.builder().mode(PermissionMode.ACCEPT_EDITS).build();
            PermissionDecision d = decide("docker ps", ctx);
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }

        @Test
        void mutatingNonDangerousIsPassthrough() {
            PermissionDecision d = decide("npm install");
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }
    }

    @Nested
    class MatchRule {

        @Test
        void nullPatternMatches() {
            assertTrue(bash.matchRule(null, Map.of("command", "ls")));
        }

        @Test
        void prefixWildcardMatchesPrefix() {
            assertTrue(bash.matchRule("git:*", Map.of("command", "git commit -m msg")));
            assertTrue(bash.matchRule("git:*", Map.of("command", "git")));
        }

        @Test
        void prefixWildcardDoesNotMatchOtherCommand() {
            assertFalse(bash.matchRule("git:*", Map.of("command", "gitignore")));
            assertFalse(bash.matchRule("git:*", Map.of("command", "docker ps")));
        }

        @Test
        void exactMatch() {
            assertTrue(bash.matchRule("ls -la", Map.of("command", "ls -la")));
            assertFalse(bash.matchRule("ls -la", Map.of("command", "ls -l")));
        }

        @Test
        void embeddedStarWildcard() {
            assertTrue(bash.matchRule("git *", Map.of("command", "git status")));
            assertTrue(bash.matchRule("git *", Map.of("command", "git")));
        }

        @Test
        void literalAsteriskEscape() {
            assertTrue(bash.matchRule("echo \\*", Map.of("command", "echo *")));
            assertFalse(bash.matchRule("echo \\*", Map.of("command", "echo foo")));
        }

        @Test
        void emptyCommandFails() {
            assertFalse(bash.matchRule("git:*", Map.of("command", "")));
        }
    }

    @Nested
    class GenerateSuggestions {

        @Test
        void gitCommitProducesMultipleSuggestions() {
            List<PermissionRule> rules =
                    bash.generateSuggestions(Map.of("command", "git commit -m \"msg\""));
            assertFalse(rules.isEmpty());
            assertTrue(rules.stream().anyMatch(r -> "git:*".equals(r.ruleContent())));
            assertTrue(rules.stream().anyMatch(r -> "git commit:*".equals(r.ruleContent())));
            for (PermissionRule rule : rules) {
                assertEquals(PermissionBehavior.ALLOW, rule.behavior());
                assertEquals("suggested", rule.source());
            }
        }

        @Test
        void emptyCommandReturnsEmpty() {
            assertTrue(bash.generateSuggestions(Map.of()).isEmpty());
            assertTrue(bash.generateSuggestions(Map.of("command", "")).isEmpty());
        }
    }

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    class Execution {

        @Test
        void runsEchoCommand() {
            ToolUseBlock use =
                    ToolUseBlock.builder()
                            .id("u1")
                            .name("Bash")
                            .input(Map.of("command", "echo hello-bash"))
                            .build();
            ToolCallParam param =
                    ToolCallParam.builder()
                            .toolUseBlock(use)
                            .input(Map.of("command", "echo hello-bash"))
                            .build();
            ToolResultBlock result = bash.callAsync(param).block();
            assertNotNull(result);
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().contains("hello-bash"));
        }

        @Test
        void rejectsBlankCommand() {
            ToolUseBlock use =
                    ToolUseBlock.builder()
                            .id("u2")
                            .name("Bash")
                            .input(Map.of("command", "   "))
                            .build();
            ToolCallParam param =
                    ToolCallParam.builder()
                            .toolUseBlock(use)
                            .input(Map.of("command", "   "))
                            .build();
            ToolResultBlock result = bash.callAsync(param).block();
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }

        @Test
        void capturesNonZeroExit() {
            ToolUseBlock use =
                    ToolUseBlock.builder()
                            .id("u3")
                            .name("Bash")
                            .input(Map.of("command", "exit 7"))
                            .build();
            ToolCallParam param =
                    ToolCallParam.builder()
                            .toolUseBlock(use)
                            .input(Map.of("command", "exit 7"))
                            .build();
            ToolResultBlock result = bash.callAsync(param).block();
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().contains("7"));
        }
    }

    @Nested
    class Configuration {

        @Test
        void additionalDangerousFilesAffectChecks() {
            Bash custom = new Bash(List.of("secret.key"), List.of());
            PermissionDecision d =
                    custom.checkPermissions(Map.of("command", "cat secret.key"), defaultCtx)
                            .block();
            assertEquals(PermissionBehavior.ASK, d.getBehavior());
        }

        @Test
        void additionalDangerousDirectoriesAffectChecks() {
            Bash custom = new Bash(List.of(), List.of("secrets"));
            PermissionDecision d =
                    custom.checkPermissions(Map.of("command", "ls secrets/key.txt"), defaultCtx)
                            .block();
            assertEquals(PermissionBehavior.ASK, d.getBehavior());
        }

        @Test
        void exposesNameAndSafetyFlags() {
            assertEquals("Bash", bash.getName());
            assertFalse(bash.isReadOnly());
            assertFalse(bash.isConcurrencySafe());
        }
    }
}
