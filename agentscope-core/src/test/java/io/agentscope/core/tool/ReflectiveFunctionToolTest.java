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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.file.WriteFileTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@code @Tool}-annotated methods are now bridged into the {@link ToolBase}
 * contract through {@link ReflectiveFunctionTool}, and that the new annotation fields propagate
 * onto the registered tool.
 */
class ReflectiveFunctionToolTest {

    static final class SafeReadTools {
        @Tool(
                name = "safe_read",
                description = "Read-only echo",
                readOnly = true,
                concurrencySafe = true)
        public String safeRead(@ToolParam(name = "key", description = "key") String key) {
            return "value:" + key;
        }
    }

    static final class StatefulTools {
        @Tool(
                name = "with_state",
                description = "Tool that reads agent state",
                stateInjected = true)
        public String withState(
                @ToolParam(name = "label", description = "label") String label, AgentState state) {
            return label + ":" + (state == null ? "<null>" : state.getSessionId());
        }
    }

    static final class MismatchedTools {
        // stateInjected=true but no AgentState parameter -> registration should fail.
        @Tool(name = "broken_state", description = "broken", stateInjected = true)
        public String broken(@ToolParam(name = "x", description = "x") String x) {
            return x;
        }
    }

    static final class UndeclaredStateTools {
        // AgentState parameter but stateInjected=false -> registration should fail.
        @Tool(name = "undeclared_state", description = "undeclared")
        public String undeclared(
                @ToolParam(name = "x", description = "x") String x, AgentState state) {
            return x + ":" + (state == null ? "<null>" : state.getSessionId());
        }
    }

    static final class ExternalTools {
        @Tool(name = "external_call", description = "external", externalTool = true)
        public String external(@ToolParam(name = "q", description = "q") String q) {
            return q;
        }
    }

    static final class DuplicateFilePathParamTools {
        // Duplicate entries must be deduplicated at registration instead of crashing.
        @Tool(
                name = "dup_path_tool",
                description = "Declares the same path param twice",
                filePathParams = {"path", "path"})
        public String dupPath(@ToolParam(name = "path", description = "path") String path) {
            return path;
        }
    }

    @Test
    void registersAsToolBaseSubclassWithAnnotationFlags() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SafeReadTools());

        AgentTool tool = toolkit.getTool("safe_read");
        ToolBase tb = assertInstanceOf(ToolBase.class, tool);
        assertTrue(tb.isReadOnly(), "readOnly flag must propagate");
        assertTrue(tb.isConcurrencySafe(), "concurrencySafe flag must propagate");
        assertFalse(tb.isExternalTool(), "externalTool default false");
        assertFalse(tb.isStateInjected(), "stateInjected default false");
        assertFalse(tb.isMcp(), "mcp default false");
    }

    @Test
    void stateInjectedToolExcludesAgentStateFromSchemaAndRegisters() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new StatefulTools());

        AgentTool tool = toolkit.getTool("with_state");
        ToolBase tb = assertInstanceOf(ToolBase.class, tool);
        assertTrue(tb.isStateInjected());

        Map<String, Object> schema = tb.getParameters();
        assertNotNull(schema.get("properties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("label"), "label parameter must be in schema");
        assertFalse(
                properties.containsKey("state"),
                "AgentState parameter must NOT appear in schema (auto-injected)");
    }

    @Test
    void stateInjectedTrueWithoutAgentStateParameterFailsAtRegistration() {
        Toolkit toolkit = new Toolkit();
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> toolkit.registerTool(new MismatchedTools()));
        assertTrue(
                ex.getMessage().contains("AgentState"),
                "error must mention AgentState requirement: " + ex.getMessage());
    }

    @Test
    void agentStateParameterWithoutStateInjectedFlagFailsAtRegistration() {
        Toolkit toolkit = new Toolkit();
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> toolkit.registerTool(new UndeclaredStateTools()));
        assertTrue(
                ex.getMessage().contains("stateInjected"),
                "error must mention stateInjected: " + ex.getMessage());
    }

    @Test
    void externalToolFlagIsRecognisedByToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExternalTools());

        assertTrue(
                toolkit.isExternalTool("external_call"),
                "@Tool(externalTool=true) must be reported as external");
        AgentTool tool = toolkit.getTool("external_call");
        ToolBase tb = assertInstanceOf(ToolBase.class, tool);
        assertTrue(tb.isExternalTool());
    }

    @Test
    void filePathParamsAnnotationPropagatesToToolBase() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WriteFileTool());

        AgentTool tool = toolkit.getTool("write_text_file");
        ToolBase tb = assertInstanceOf(ToolBase.class, tool);
        assertTrue(
                tb.getFilePathParams().contains("file_path"),
                "@Tool(filePathParams) must propagate to the registered tool");
    }

    @Test
    void duplicateFilePathParamsAreDeduplicatedAtRegistration() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DuplicateFilePathParamTools());

        assertNotNull(toolkit.getTool("dup_path_tool"), "tool must register despite duplicates");
        ToolBase tb = assertInstanceOf(ToolBase.class, toolkit.getTool("dup_path_tool"));
        assertEquals(1, tb.getFilePathParams().size(), "duplicates must collapse");
    }

    @Test
    void builtinWriteFileToolPathChecksFlowThroughRealRegistration(@TempDir Path workDir) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WriteFileTool());
        ToolBase writeTool = assertInstanceOf(ToolBase.class, toolkit.getTool("write_text_file"));

        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder()
                                .mode(PermissionMode.ACCEPT_EDITS)
                                .addWorkingDirectory(
                                        workDir.toString(),
                                        new AdditionalWorkingDirectory(workDir.toString(), "test"))
                                .build());

        assertEquals(
                PermissionBehavior.ALLOW,
                engine.checkPermission(
                                writeTool,
                                Map.of("file_path", workDir.resolve("new.txt").toString()))
                        .block()
                        .getBehavior(),
                "in-scope write must be auto-allowed through the real registration path");
        assertEquals(
                PermissionBehavior.ASK,
                engine.checkPermission(writeTool, Map.of("file_path", "/etc/evil.txt"))
                        .block()
                        .getBehavior(),
                "out-of-scope write must fall back to the engine default ASK");
        assertEquals(
                PermissionBehavior.ASK,
                engine.checkPermission(
                                writeTool,
                                Map.of("file_path", workDir.resolve(".bashrc").toString()))
                        .block()
                        .getBehavior(),
                "dangerous path must Safety-ASK even inside the working directory");
    }

    @Test
    void builtinWriteFileToolWithBaseDirCatchesDangerousResolverLanding(@TempDir Path sshProj)
            throws IOException {
        Path sshDir = sshProj.resolve(".ssh");
        Files.createDirectories(sshDir);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WriteFileTool(sshProj.toString()));
        ToolBase writeTool = assertInstanceOf(ToolBase.class, toolkit.getTool("write_text_file"));

        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder().mode(PermissionMode.BYPASS).build());

        assertEquals(
                PermissionBehavior.ASK,
                engine.checkPermission(writeTool, Map.of("file_path", ".ssh/config"))
                        .block()
                        .getBehavior(),
                "the baseDir resolver landing (.ssh/config) must trip the bypass-immune safety"
                        + " check");
    }

    @Test
    void builtinWriteFileToolRelativePathWithBaseDirAllowsInScope(@TempDir Path workDir) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WriteFileTool(workDir.toString()));
        ToolBase writeTool = assertInstanceOf(ToolBase.class, toolkit.getTool("write_text_file"));

        PermissionEngine engine =
                new PermissionEngine(
                        PermissionContextState.builder()
                                .mode(PermissionMode.ACCEPT_EDITS)
                                .addWorkingDirectory(
                                        workDir.toString(),
                                        new AdditionalWorkingDirectory(workDir.toString(), "test"))
                                .build());

        assertEquals(
                PermissionBehavior.ALLOW,
                engine.checkPermission(writeTool, Map.of("file_path", "a.txt"))
                        .block()
                        .getBehavior(),
                "relative path with the baseDir resolver must auto-allow inside the scope");
    }
}
