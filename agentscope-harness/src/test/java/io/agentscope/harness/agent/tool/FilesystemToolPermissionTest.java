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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FilesystemToolPermissionTest {

    @ParameterizedTest
    @ValueSource(strings = {"write_file", "edit_file"})
    void denyDefinitionWritesEvenInBypassMode(String toolName) {
        AbstractFilesystem filesystem = mock(AbstractFilesystem.class);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FilesystemTool(filesystem));
        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder().mode(PermissionMode.BYPASS).build());
        engine.addRule(new PermissionRule(toolName, "AGENTS.md", PermissionBehavior.DENY, "test"));
        engine.addRule(new PermissionRule(toolName, "skills/**", PermissionBehavior.DENY, "test"));
        ToolBase tool = (ToolBase) toolkit.getTool(toolName);

        for (String path : new String[] {"AGENTS.md", "skills/demo/SKILL.md"}) {
            assertEquals(
                    PermissionBehavior.DENY,
                    engine.checkPermission(tool, Map.of("path", path)).block().getBehavior(),
                    path);
        }
        assertEquals(
                PermissionBehavior.ALLOW,
                engine.checkPermission(tool, Map.of("path", "reports/result.txt"))
                        .block()
                        .getBehavior());
        verifyNoInteractions(filesystem);
    }

    @ParameterizedTest
    @CsvSource({
        "AGENTS.md, ./AGENTS.md, true",
        "AGENTS.md, /AGENTS.md, true",
        "AGENTS.md, skills/../AGENTS.md, true",
        "AGENTS.md, notes/AGENTS.md, false",
        "skills/**, skills, true",
        "skills/**, skills/demo/SKILL.md, true",
        "skills/**, ./skills//demo/./SKILL.md, true",
        "skills/**, skills-old/SKILL.md, false",
        "skills/*.md, skills/readme.md, true",
        "skills/*.md, skills/demo/readme.md, false",
        "skills/**/SKILL.md, skills/SKILL.md, true",
        "skills/**/SKILL.md, skills/demo/nested/SKILL.md, true",
        "**/AGENTS.md, AGENTS.md, true",
        "**/AGENTS.md, nested/AGENTS.md, true",
        "notes/?.txt, notes/a.txt, true",
        "notes/?.txt, notes/ab.txt, false",
        "[draft].md, [draft].md, true",
        "[draft].md, d.md, false",
        "AGENTS.md, agents.md, false",
        "skills/**, skills/../report.txt, false"
    })
    void matchesPortablePathPatterns(String pattern, String path, boolean expected) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FilesystemTool(mock(AbstractFilesystem.class)));
        ToolBase tool = (ToolBase) toolkit.getTool("write_file");
        assertEquals(expected, tool.matchRule(pattern, Map.of("path", path)));
    }

    @Test
    void normalizesWindowsAndNamespacedWorkspaceAliasesPerCall() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                new FilesystemTool(
                        mock(AbstractFilesystem.class),
                        WorkspacePathNormalizer.of(
                                "C:/workspace", rc -> List.of(rc.getUserId(), rc.getSessionId()))));
        ToolBase tool = (ToolBase) toolkit.getTool("write_file");
        for (String user : List.of("alice", "bob", "alice")) {
            RuntimeContext context =
                    RuntimeContext.builder().userId(user).sessionId("session").build();
            String path = "C:\\workspace\\" + user + "\\session\\skills\\demo\\SKILL.md";
            assertTrue(tool.matchRule("skills/**", Map.of("path", path), context));
            assertTrue(
                    tool.matchRule(
                            "skills/**",
                            Map.of(
                                    "path",
                                    "C:/workspace/" + user + "/session/skills/./demo/SKILL.md"),
                            context));
            assertFalse(
                    tool.matchRule(
                            "skills/**",
                            Map.of("path", "C:/elsewhere/skills/demo/SKILL.md"),
                            context));
        }
    }

    @Test
    void matchesUncWorkspacePrefixBeforeNormalizingSeparators() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                new FilesystemTool(
                        mock(AbstractFilesystem.class),
                        WorkspacePathNormalizer.of("//server/share/workspace")));
        ToolBase tool = (ToolBase) toolkit.getTool("edit_file");
        assertTrue(
                tool.matchRule("AGENTS.md", Map.of("path", "//server/share/workspace/AGENTS.md")));
        assertTrue(
                tool.matchRule(
                        "AGENTS.md",
                        Map.of("path", "//server/share/workspace/AGENTS.md".replace('/', '\\'))));
    }

    @ParameterizedTest
    @CsvSource({
        "/tmp/workspace, /tmp/other/../workspace/AGENTS.md",
        "/tmp/old/../workspace, /tmp/workspace/AGENTS.md",
        "//server/share/workspace, //server/share/other/../workspace/AGENTS.md",
        "C:/workspace, C:/other/../workspace/AGENTS.md"
    })
    void matchesAbsoluteAliasesAgainstNormalizedWorkspacePrefixes(String prefix, String path) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                new FilesystemTool(
                        mock(AbstractFilesystem.class), WorkspacePathNormalizer.of(prefix)));
        ToolBase tool = (ToolBase) toolkit.getTool("edit_file");
        assertTrue(tool.matchRule("AGENTS.md", Map.of("path", path)));
    }

    @Test
    void usesTheSameNamespacePrefixMappingAsExecution() {
        Toolkit toolkit = new Toolkit();
        WorkspacePathNormalizer normalizer =
                WorkspacePathNormalizer.of(
                        "/workspace", rc -> List.of(rc.getUserId(), rc.getSessionId()));
        toolkit.registerTool(new FilesystemTool(mock(AbstractFilesystem.class), normalizer));
        ToolBase tool = (ToolBase) toolkit.getTool("write_file");
        RuntimeContext context =
                RuntimeContext.builder().userId("alice").sessionId("session").build();
        String path = "/workspace/./alice/session/AGENTS.md";
        assertEquals("./alice/session/AGENTS.md", normalizer.normalize(path, context));
        assertFalse(tool.matchRule("AGENTS.md", Map.of("path", path), context));
        assertTrue(tool.matchRule("alice/session/AGENTS.md", Map.of("path", path), context));
    }

    @Test
    void relativeParentPathsFailClosedForPathRulesButKeepToolWideRules() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FilesystemTool(mock(AbstractFilesystem.class)));
        ToolBase tool = (ToolBase) toolkit.getTool("edit_file");
        for (String path :
                List.of(
                        "..",
                        "../workspace/AGENTS.md",
                        "notes/../../workspace/AGENTS.md",
                        "C:AGENTS.md",
                        "C:")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> tool.matchRule("AGENTS.md", Map.of("path", path)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> tool.matchRule("reports/**", Map.of("path", path)));
            assertTrue(tool.matchRule(null, Map.of("path", path)));
        }
    }

    @Test
    void emptyRulesKeepToolWideSemanticsAndInvalidInputsDoNotMatchPathRules() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FilesystemTool(mock(AbstractFilesystem.class)));
        ToolBase tool = (ToolBase) toolkit.getTool("write_file");
        assertTrue(tool.matchRule(null, Map.of()));
        assertTrue(tool.matchRule("", Map.of()));
        assertFalse(tool.matchRule("**", Map.of()));
        assertFalse(tool.matchRule("**", Map.of("path", 1)));
        assertFalse(tool.matchRule("**", Map.of("path", " ")));
        assertFalse(tool.matchRule("**", Map.of("path", "bad\0path")));
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.matchRule("skills**", Map.of("path", "skills/demo")));
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.matchRule("skills/../**", Map.of("path", "skills/demo")));
    }

    @Test
    void pathRulesRetainDenyAskAllowPriorityAndReadsRemainIndependent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FilesystemTool(mock(AbstractFilesystem.class)));
        ToolBase write = (ToolBase) toolkit.getTool("write_file");
        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder().mode(PermissionMode.BYPASS).build());
        engine.addRule(new PermissionRule("write_file", "**", PermissionBehavior.ALLOW, "test"));
        engine.addRule(
                new PermissionRule("write_file", "skills/**", PermissionBehavior.ASK, "test"));
        engine.addRule(
                new PermissionRule(
                        "write_file", "skills/private/**", PermissionBehavior.DENY, "test"));
        assertEquals(
                PermissionBehavior.DENY,
                engine.checkPermission(write, Map.of("path", "skills/private/SKILL.md"))
                        .block()
                        .getBehavior());
        assertEquals(
                PermissionBehavior.ASK,
                engine.checkPermission(write, Map.of("path", "skills/demo/SKILL.md"))
                        .block()
                        .getBehavior());
        assertEquals(
                PermissionBehavior.ALLOW,
                engine.checkPermission(write, Map.of("path", "report.txt")).block().getBehavior());
        assertEquals(
                PermissionBehavior.ALLOW,
                engine.checkPermission(
                                (ToolBase) toolkit.getTool("read_file"),
                                Map.of("path", "skills/private/SKILL.md"))
                        .block()
                        .getBehavior());
    }
}
