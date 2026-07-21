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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WorkspaceContextMiddlewareTest {

    @Test
    void extractDescriptionFromFrontmatter_simpleDescription() {
        String content =
                "---\n"
                        + "description: Code review specialist\n"
                        + "workspace:\n"
                        + "  mode: isolated\n"
                        + "---\n"
                        + "\n"
                        + "You are a code review subagent.";
        String desc = WorkspaceContextMiddleware.extractDescriptionFromFrontmatter(content);
        assertNotNull(desc);
        assertEquals("Code review specialist", desc);
    }

    @Test
    void extractDescriptionFromFrontmatter_quotedDescription() {
        String content =
                "---\n"
                        + "description: \"A long description with special chars: @#$\"\n"
                        + "---\n"
                        + "\n"
                        + "Body text.";
        String desc = WorkspaceContextMiddleware.extractDescriptionFromFrontmatter(content);
        assertNotNull(desc);
        assertEquals("\"A long description with special chars: @#$\"", desc);
    }

    @Test
    void extractDescriptionFromFrontmatter_noDescription() {
        String content = "---\n" + "workspace:\n" + "  mode: shared\n" + "---\n" + "\n" + "Body.";
        String desc = WorkspaceContextMiddleware.extractDescriptionFromFrontmatter(content);
        assertNull(desc);
    }

    @Test
    void extractDescriptionFromFrontmatter_noFrontmatter() {
        String content = "Just a plain markdown file without frontmatter.";
        String desc = WorkspaceContextMiddleware.extractDescriptionFromFrontmatter(content);
        assertNull(desc);
    }

    @Test
    void extractDescriptionFromFrontmatter_nullContent() {
        assertNull(WorkspaceContextMiddleware.extractDescriptionFromFrontmatter(null));
    }

    @Test
    void extractDescriptionFromFrontmatter_blankContent() {
        assertNull(WorkspaceContextMiddleware.extractDescriptionFromFrontmatter("   "));
    }

    @Test
    void extractDescriptionFromFrontmatter_unclosedFrontmatter() {
        String content = "---\n" + "description: Test\n" + "no closing frontmatter";
        assertNull(WorkspaceContextMiddleware.extractDescriptionFromFrontmatter(content));
    }

    @Test
    void extractDescriptionFromFrontmatter_emptyDescription() {
        String content = "---\n" + "description:\n" + "---\n" + "Body.";
        String desc = WorkspaceContextMiddleware.extractDescriptionFromFrontmatter(content);
        // Empty description value — should return empty string
        assertNotNull(desc);
        assertEquals("", desc);
    }
}
