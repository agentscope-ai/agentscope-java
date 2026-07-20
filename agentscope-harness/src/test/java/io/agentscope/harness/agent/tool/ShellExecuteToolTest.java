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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ShellExecuteTool}, with focus on the OS-aware command assembly
 * introduced for cross-platform {@code cd} prefix handling.
 */
class ShellExecuteToolTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    private AbstractSandboxFilesystem sandbox;
    private ShellExecuteTool tool;
    private String previousOsName;

    @BeforeEach
    void setUp() {
        sandbox = mock(AbstractSandboxFilesystem.class);
        tool = new ShellExecuteTool(sandbox);
        previousOsName = System.getProperty("os.name");
        when(sandbox.execute(any(), any(), anyInt())).thenReturn(new ExecuteResponse("", 0, false));
    }

    @AfterEach
    void restoreOsName() {
        if (previousOsName != null) {
            System.setProperty("os.name", previousOsName);
        } else {
            System.clearProperty("os.name");
        }
    }

    private void setOsName(String osName) {
        System.setProperty("os.name", osName);
    }

    private String capturedCommand() {
        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        verify(sandbox).execute(any(), cmd.capture(), anyInt());
        return cmd.getValue();
    }

    // --- working_directory handling ---

    @Test
    void nullWorkingDirectory_passesCommandUnchanged() {
        tool.execute(RT, "ls -la", null, 10);

        assertEquals("ls -la", capturedCommand());
    }

    @Test
    void blankWorkingDirectory_passesCommandUnchanged() {
        tool.execute(RT, "pwd", "   ", 10);

        assertEquals("pwd", capturedCommand());
    }

    @Test
    void emptyWorkingDirectory_passesCommandUnchanged() {
        tool.execute(RT, "pwd", "", 10);

        assertEquals("pwd", capturedCommand());
    }

    // --- path validation (must stay relative within the workspace) ---

    @Test
    void absolutePathWorkingDirectory_returnsErrorAndDoesNotInvokeSandbox() {
        String result = tool.execute(RT, "ls", "/etc", 10);

        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("relative path"));
        verify(sandbox, never()).execute(any(), any(), anyInt());
    }

    @Test
    void tildeWorkingDirectory_returnsError() {
        String result = tool.execute(RT, "ls", "~/dir", 10);

        assertTrue(result.startsWith("Error:"));
        verify(sandbox, never()).execute(any(), any(), anyInt());
    }

    @Test
    void dotDotWorkingDirectory_returnsError() {
        String result = tool.execute(RT, "ls", "../secret", 10);

        assertTrue(result.startsWith("Error:"));
        verify(sandbox, never()).execute(any(), any(), anyInt());
    }

    // --- Windows command assembly (lines 63-66) ---

    @Test
    void windowsPlatform_wrapsCommandWithCmdCdSyntax() {
        setOsName("Windows 11");

        tool.execute(RT, "dir", "src\\main", 15);

        assertEquals("cd \"src\\main\" & dir", capturedCommand());
    }

    @Test
    void windowsPlatform_caseInsensitiveOsNameDetection() {
        setOsName("windows 10");

        tool.execute(RT, "echo hi", "build", 10);

        assertEquals("cd \"build\" & echo hi", capturedCommand());
    }

    @Test
    void windowsPlatform_escapesEmbeddedDoubleQuotes() {
        setOsName("Windows Server 2022");

        tool.execute(RT, "cmd", "a\"b\\c", 10);

        // Each embedded double quote is escaped as \" so cmd.exe parses it correctly.
        assertEquals("cd \"a\\\"b\\c\" & cmd", capturedCommand());
    }

    @Test
    void windowsPlatform_stripsWorkingDirectoryBeforeQuoting() {
        setOsName("Windows 10");

        tool.execute(RT, "git status", "  repo  ", 10);

        assertEquals("cd \"repo\" & git status", capturedCommand());
    }

    // --- Unix command assembly (lines 67-69) ---

    @Test
    void unixPlatform_wrapsCommandWithShellCdSyntax() {
        setOsName("Linux");

        tool.execute(RT, "ls -la", "src/main", 15);

        assertEquals("cd 'src/main' && ls -la", capturedCommand());
    }

    @Test
    void macOsPlatform_usesUnixSyntax() {
        setOsName("Mac OS X");

        tool.execute(RT, "pwd", "build/classes", 10);

        assertEquals("cd 'build/classes' && pwd", capturedCommand());
    }

    @Test
    void unixPlatform_escapesEmbeddedSingleQuotes() {
        setOsName("Linux");

        tool.execute(RT, "echo hi", "it's here", 10);

        // Standard POSIX single-quote escape: close quote, escaped quote, reopen quote.
        assertEquals("cd 'it'\\''s here' && echo hi", capturedCommand());
    }

    // --- timeout handling ---

    @Test
    void positiveTimeout_isForwardedAsIs() {
        tool.execute(RT, "pwd", null, 42);

        verify(sandbox).execute(eq(RT), any(), eq(42));
    }

    @Test
    void zeroTimeout_fallsBackToDefault30() {
        tool.execute(RT, "pwd", null, 0);

        verify(sandbox).execute(eq(RT), any(), eq(30));
    }

    @Test
    void negativeTimeout_fallsBackToDefault30() {
        tool.execute(RT, "pwd", null, -5);

        verify(sandbox).execute(eq(RT), any(), eq(30));
    }

    // --- result formatting ---

    @Test
    void successfulResponse_includesExitCodeAndOutput() {
        when(sandbox.execute(any(), any(), anyInt()))
                .thenReturn(new ExecuteResponse("hello\nworld", 0, false));

        String result = tool.execute(RT, "echo hello", null, 10);

        assertTrue(result.contains("Exit code: 0"));
        assertTrue(result.contains("hello\nworld"));
        assertFalse(result.contains("truncated"));
    }

    @Test
    void nonZeroExitCode_isReported() {
        when(sandbox.execute(any(), any(), anyInt()))
                .thenReturn(new ExecuteResponse("boom", 127, false));

        String result = tool.execute(RT, "false", null, 10);

        assertTrue(result.contains("Exit code: 127"));
        assertTrue(result.contains("boom"));
    }

    @Test
    void truncatedOutput_appendsTruncationNotice() {
        when(sandbox.execute(any(), any(), anyInt()))
                .thenReturn(new ExecuteResponse("partial", 0, true));

        String result = tool.execute(RT, "cat big", null, 10);

        assertTrue(result.contains("(output was truncated)"));
    }

    @Test
    void blankOutput_omitsOutputSection() {
        when(sandbox.execute(any(), any(), anyInt()))
                .thenReturn(new ExecuteResponse("  ", 0, false));

        String result = tool.execute(RT, "true", null, 10);

        assertEquals("Exit code: 0", result.strip());
    }

    @Test
    void nullOutput_omitsOutputSection() {
        when(sandbox.execute(any(), any(), anyInt()))
                .thenReturn(new ExecuteResponse(null, 0, false));

        String result = tool.execute(RT, "true", null, 10);

        assertEquals("Exit code: 0", result.strip());
    }
}
