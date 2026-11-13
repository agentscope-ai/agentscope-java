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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceContextMiddlewareRulesContentTest {
    @TempDir Path workspace;
    private static final int CAP = 100 * 1024;
    private static final String MARKER = "[File truncated: AGENTS.md]";

    private String prompt(String content) {
        var manager =
                new WorkspaceManager(workspace) {
                    @Override
                    public String readAgentsMd(RuntimeContext rc) {
                        return content;
                    }
                };
        return new WorkspaceContextMiddleware(manager)
                .onSystemPrompt(null, RuntimeContext.empty(), "BASE")
                .block();
    }

    private String rules(String prompt) {
        String start = "<agents_context>\n  ";
        int begin = prompt.indexOf(start) + start.length();
        return prompt.substring(begin, prompt.indexOf("\n  </agents_context>", begin))
                .replace("\n  ", "\n");
    }

    @Test
    void boundaryAndLiteralContentStayUnchanged() {
        String content = "a".repeat(CAP);
        assertEquals(content, rules(prompt(content)));
        assertEquals(content + "\n\n" + MARKER, rules(prompt(content + "z")));
        assertEquals("mvn test && List<String>", rules(prompt("  mvn test && List<String>  ")));
        assertFalse(prompt("").contains(MARKER));
    }

    @Test
    void everyUtf8BoundaryStopsBeforeIncompleteCharacter() {
        for (String character : new String[] {"a", "é", "中", "😀"}) {
            int bytes = character.getBytes(StandardCharsets.UTF_8).length;
            for (int remaining = 0; remaining < bytes; remaining++) {
                String head = "x".repeat(CAP - remaining);
                String body = rules(prompt(head + character + "TAIL"));
                assertEquals(head + "\n\n" + MARKER, body);
                assertTrue(head.getBytes(StandardCharsets.UTF_8).length <= CAP);
                assertFalse(body.contains("\uFFFD"));
            }
            String exact = "x".repeat(CAP - bytes) + character;
            assertEquals(exact, rules(prompt(exact)));
        }
    }
}
