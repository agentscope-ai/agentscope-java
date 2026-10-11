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
package io.agentscope.core.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.AgentTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A registered MCP tool must keep the server's human-readable display title reachable through the
 * public {@link AgentTool} surface, so a caller rendering a permission confirmation can label the
 * operation for an end user instead of showing {@code repair__create_ticket}.
 *
 * <p>The title is also hostile input: it is a string the MCP server chose, displayed to a human who
 * is about to approve an action, so these tests pin the stripping and the length cap too.
 */
class McpToolTitleTest {

    private McpClientWrapper clientWrapper;
    private Map<String, Object> parameters;

    @BeforeEach
    void setUp() {
        clientWrapper = McpClientWrapperTestSupport.mockWrapper("test-client", true);
        parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", new HashMap<>());
    }

    private static McpSchema.Tool toolWith(String title, String annotationTitle) {
        return new McpSchema.Tool(
                "create_ticket",
                title,
                "Creates a repair ticket",
                null,
                null,
                annotationTitle == null
                        ? null
                        : new McpSchema.ToolAnnotations(
                                annotationTitle, null, null, null, null, null),
                null);
    }

    @Test
    void resolveDisplayTitle_prefersToolTitle() {
        McpSchema.Tool tool = toolWith("Repair Ticket", "Annotation Title");

        assertEquals("Repair Ticket", McpTool.resolveDisplayTitle(tool));
    }

    @Test
    void resolveDisplayTitle_fallsBackToAnnotationTitle() {
        McpSchema.Tool tool = toolWith(null, "Repair Ticket");

        assertEquals("Repair Ticket", McpTool.resolveDisplayTitle(tool));
    }

    @Test
    void resolveDisplayTitle_skipsBlankTitles() {
        McpSchema.Tool blank =
                new McpSchema.Tool("create_ticket", "   ", "desc", null, null, null, null);
        assertNull(McpTool.resolveDisplayTitle(blank), "a blank title is no title");

        assertNull(
                McpTool.resolveDisplayTitle(null),
                "a missing tool definition must not fail the registration");
    }

    @Test
    void resolveDisplayTitle_absentAnnotationsYieldsNull() {
        McpSchema.Tool tool =
                new McpSchema.Tool("create_ticket", null, "desc", null, null, null, null);

        assertNull(McpTool.resolveDisplayTitle(tool));
    }

    // ==================== Display sanitising of server-controlled input ====================

    @Test
    void resolveDisplayTitle_dropsZeroWidthAndBidiControlsWithoutAddingSpace() {
        // U+202E/U+202D reorder the rest of the line, U+200C and U+00AD are invisible: none of them
        // may survive into a confirmation prompt, and none of them is a word separator either.
        assertEquals(
                "DeleteThisNow",
                McpTool.resolveDisplayTitle(toolWith("Delete\u202EThis\u200CNow", null)),
                "bidi and zero-width controls must vanish, not be escaped");
        assertEquals(
                "RepairTicket", McpTool.resolveDisplayTitle(toolWith("Repair\u00ADTicket", null)));
    }

    @Test
    void resolveDisplayTitle_collapsesNewlinesAndControlsIntoOneSpace() {
        assertEquals(
                "Line one Line two",
                McpTool.resolveDisplayTitle(toolWith("  Line one\n\tLine   two  ", null)),
                "the label stays on a single line");
        assertEquals(
                "A B",
                McpTool.resolveDisplayTitle(toolWith("A\u0000\u0007B", null)),
                "C0 controls separate but never pass through");
    }

    @Test
    void resolveDisplayTitle_treatsUnprintableTitleAsAbsentAndFallsBack() {
        McpSchema.Tool tool = toolWith("\n\u200F ", "Repair Ticket");

        assertEquals(
                "Repair Ticket",
                McpTool.resolveDisplayTitle(tool),
                "a title with nothing printable left is no title");
    }

    @Test
    void resolveDisplayTitle_capsLengthAndMarksTruncation() {
        String longTitle = "x".repeat(200);
        String resolved = McpTool.resolveDisplayTitle(toolWith(longTitle, null));

        assertEquals(120, resolved.length(), "the cap is inclusive of the ellipsis");
        assertEquals("x".repeat(117) + "...", resolved);
    }

    @Test
    void resolveDisplayTitle_truncationNeverSplitsASurrogatePair() {
        String title = "y".repeat(116) + "\uD83D\uDE00tail";
        String resolved = McpTool.resolveDisplayTitle(toolWith(title, null));

        assertEquals("y".repeat(116) + "...", resolved);
        for (int i = 0; i < resolved.length(); i++) {
            assertFalse(
                    Character.isSurrogate(resolved.charAt(i)),
                    "a lone surrogate would corrupt every consumer that re-encodes the label");
        }
    }

    @Test
    void registeredTool_sanitisesATitleHandedStraightToTheConstructor() {
        // Registration is not the only producer: extensions build McpTool directly, so the guard
        // has
        // to sit where the value is stored rather than only in resolveDisplayTitle.
        McpTool tool =
                new McpTool(
                        "mcp__create_ticket",
                        "create_ticket",
                        "Creates a repair ticket",
                        parameters,
                        null,
                        clientWrapper,
                        null,
                        "test-client",
                        false,
                        "Delete\u202EAll\u0000Data\nnow");

        assertEquals("DeleteAll Data now", tool.getTitle());
        assertTrue(
                tool.getTitle().length() <= 120,
                "the stored title is already display-safe for a prompt");
    }

    @Test
    void registeredTool_exposesTitleThroughAgentToolSurface() {
        McpTool tool =
                new McpTool(
                        "mcp__create_ticket",
                        "create_ticket",
                        "Creates a repair ticket",
                        parameters,
                        null,
                        clientWrapper,
                        null,
                        "test-client",
                        false,
                        "Repair Ticket");

        // Toolkit hands callers an AgentTool, so the title has to be reachable at that width.
        AgentTool asAgentTool = tool;
        assertEquals("Repair Ticket", asAgentTool.getTitle());
        assertEquals(
                "mcp__create_ticket",
                asAgentTool.getName(),
                "the programmatic name stays untouched by the display title");
    }

    @Test
    void registeredToolWithoutTitle_returnsNullRatherThanThrowing() {
        McpTool tool =
                new McpTool(
                        "create_ticket",
                        "Creates a repair ticket",
                        parameters,
                        null,
                        clientWrapper,
                        null,
                        "test-client",
                        false);

        assertNull(tool.getTitle(), "servers that send no title keep working unchanged");
    }
}
