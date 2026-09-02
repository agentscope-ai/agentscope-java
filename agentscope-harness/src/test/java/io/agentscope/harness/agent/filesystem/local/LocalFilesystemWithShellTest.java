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
package io.agentscope.harness.agent.filesystem.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LocalFilesystemWithShellTest {

    @Test
    void outputCharset_usesNativeEncodingOnWindows() {
        assertEquals(
                Charset.forName("windows-1252"),
                LocalFilesystemWithShell.outputCharset("Windows 10", "windows-1252"));
    }

    @Test
    void outputCharset_usesUtf8OnNonWindowsSystems() {
        assertEquals(StandardCharsets.UTF_8, LocalFilesystemWithShell.outputCharset("Linux"));
    }

    @Test
    void outputCharset_fallsBackToDefaultWhenWindowsNativeEncodingIsUnavailable() {
        assertEquals(
                Charset.defaultCharset(),
                LocalFilesystemWithShell.outputCharset("Windows 10", null));
    }

    @Test
    void execute_drainsLargeStdoutWithoutDeadlocking() throws Exception {
        Path root = Files.createTempDirectory("local-filesystem-shell-test");
        LocalFilesystemWithShell filesystem = new LocalFilesystemWithShell(root);

        String command;
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            command = "for /l %i in (1,1,12000) do @echo 123456789";
        } else {
            command = "i=0; while [ $i -lt 12000 ]; do printf '123456789'; i=$((i+1)); done";
        }
        var response = filesystem.execute(null, command, 10);

        assertEquals(0, response.exitCode());
        assertTrue(response.output().contains("Output truncated"));
    }

    @Test
    void execute_drainsLargeStderrWithoutDeadlocking() throws Exception {
        Path root = Files.createTempDirectory("local-filesystem-shell-test");
        LocalFilesystemWithShell filesystem = new LocalFilesystemWithShell(root);

        String command;
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            command = "for /l %i in (1,1,12000) do @echo 123456789 1>&2";
        } else {
            command = "i=0; while [ $i -lt 12000 ]; do printf '123456789' >&2; i=$((i+1)); done";
        }
        var response = filesystem.execute(null, command, 10);

        assertEquals(0, response.exitCode());
        assertTrue(response.output().contains("[stderr]"));
        assertTrue(response.output().contains("Output truncated"));
    }

    @Test
    void execute_terminatesCommandsThatExceedTheTimeout() throws Exception {
        Path root = Files.createTempDirectory("local-filesystem-shell-test");
        LocalFilesystemWithShell filesystem = new LocalFilesystemWithShell(root);

        String command;
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            command = "ping -n 4 127.0.0.1 > nul";
        } else {
            command = "sleep 10";
        }
        var response = filesystem.execute(null, command, 1);

        assertEquals(124, response.exitCode());
        assertTrue(response.output().contains("timed out after 1 seconds"));
    }
}
