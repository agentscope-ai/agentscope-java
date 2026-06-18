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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ShellExecuteToolTest {

    @Test
    void executeQuotesWorkingDirectoryBeforeShellCommand() {
        AbstractSandboxFilesystem sandbox = mock(AbstractSandboxFilesystem.class);
        ShellExecuteTool tool = new ShellExecuteTool(sandbox);
        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId("sess-1").build();

        when(sandbox.execute(any(), anyString(), anyInt()))
                .thenReturn(new ExecuteResponse("hello", 0, false));

        String result = tool.execute(runtimeContext, "pwd", "nested dir/o'clock", 15);

        assertEquals("Exit code: 0\n\nhello", result);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandbox).execute(same(runtimeContext), commandCaptor.capture(), eq(15));
        assertEquals("cd 'nested dir/o'\\''clock' && pwd", commandCaptor.getValue());
    }

    @Test
    void executeRejectsUnsafeWorkingDirectoryValues() {
        ShellExecuteTool tool = new ShellExecuteTool(mock(AbstractSandboxFilesystem.class));
        RuntimeContext runtimeContext = RuntimeContext.empty();

        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(runtimeContext, "pwd", "/tmp", 15));
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(runtimeContext, "pwd", "C:\\tmp", 15));
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(runtimeContext, "pwd", "\\\\server\\share", 15));
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(runtimeContext, "pwd", "safe\0dir", 15));
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(runtimeContext, "pwd", "../outside", 15));
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(runtimeContext, "pwd", "safe/../../outside", 15));
    }

    @Test
    void executeLeavesCommandUnchangedWhenWorkingDirectoryIsMissing() {
        AbstractSandboxFilesystem sandbox = mock(AbstractSandboxFilesystem.class);
        ShellExecuteTool tool = new ShellExecuteTool(sandbox);
        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId("sess-1").build();

        when(sandbox.execute(any(), anyString(), anyInt()))
                .thenReturn(new ExecuteResponse(null, 0, false));

        String result = tool.execute(runtimeContext, "pwd", null, 15);

        assertEquals("Exit code: 0\n", result);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(sandbox).execute(same(runtimeContext), commandCaptor.capture(), eq(15));
        assertEquals("pwd", commandCaptor.getValue());
    }

    @Test
    void executeAppendsTruncatedMarkerWhenSandboxResultIsTruncated() {
        AbstractSandboxFilesystem sandbox = mock(AbstractSandboxFilesystem.class);
        ShellExecuteTool tool = new ShellExecuteTool(sandbox);
        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId("sess-1").build();

        when(sandbox.execute(any(), anyString(), anyInt()))
                .thenReturn(new ExecuteResponse("hello", 0, true));

        String result = tool.execute(runtimeContext, "pwd", "nested dir", 15);

        assertEquals("Exit code: 0\n\nhello\n(output was truncated)", result);
    }

    @Test
    void validateWorkingDirectoryRejectsNullOrBlank() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShellExecuteTool.validateWorkingDirectory(null, Path::of));
        assertThrows(
                IllegalArgumentException.class,
                () -> ShellExecuteTool.validateWorkingDirectory("   ", Path::of));
    }

    @Test
    void validateWorkingDirectoryWrapsParserFailures() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ShellExecuteTool.validateWorkingDirectory(
                                "safe/path",
                                value -> {
                                    throw new InvalidPathException(value, "forced failure");
                                }));
    }
}
