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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.agentscope.harness.agent.filesystem.local.ShellResolver.ResolvedShell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellResolverTest {

    @Test
    void resolve_runsCommandsThroughConfiguredPath(@TempDir Path dir) throws IOException {
        Path shell = Files.createFile(dir.resolve("my-shell"));

        ResolvedShell resolved =
                ShellResolver.resolve(shell.toString(), "Linux", Map.of(), Files::isRegularFile);

        assertEquals(shell.toString(), resolved.executable());
        assertEquals("-c", resolved.commandFlag());
        assertEquals(List.of(shell.toString(), "-c", "echo hi"), resolved.commandLine("echo hi"));
    }

    @Test
    void resolve_rejectsMissingConfiguredPath(@TempDir Path dir) {
        Path missing = dir.resolve("no-such-shell");

        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ShellResolver.resolve(
                                        missing.toString(),
                                        "Linux",
                                        Map.of(),
                                        Files::isRegularFile));

        assertTrue(
                thrown.getMessage().contains(missing.toString()),
                "message should name the path, was: " + thrown.getMessage());
    }

    @Test
    void resolve_looksUpBareNameOnPosixPath(@TempDir Path dir) throws IOException {
        // A Windows temp directory carries a drive-letter colon, which a POSIX PATH would split on.
        assumeFalse(isWindowsHost(), "POSIX PATH semantics need a POSIX host path");
        Path bash = Files.createFile(dir.resolve("bash"));

        ResolvedShell resolved =
                ShellResolver.resolve(
                        "bash", "Linux", Map.of("PATH", dir.toString()), Files::isRegularFile);

        assertEquals(bash.toString(), resolved.executable());
        assertEquals("-c", resolved.commandFlag());
    }

    @Test
    void resolve_appendsExeSuffixWhenLookingUpNameOnWindows(@TempDir Path dir) throws IOException {
        Path bash = Files.createFile(dir.resolve("bash.exe"));

        ResolvedShell resolved =
                ShellResolver.resolve(
                        "bash", "Windows 11", Map.of("PATH", dir.toString()), Files::isRegularFile);

        assertEquals(bash.toString(), resolved.executable());
        assertEquals("-c", resolved.commandFlag());
    }

    @Test
    void resolve_findsNameInGitForWindowsInstallWhenNotOnPath(@TempDir Path dir)
            throws IOException {
        Path gitSh = Files.createDirectories(dir.resolve("Git/bin")).resolve("sh.exe");
        Files.createFile(gitSh);

        ResolvedShell resolved =
                ShellResolver.resolve(
                        "sh",
                        "Windows 11",
                        Map.of("ProgramFiles", dir.toString(), "PATH", ""),
                        Files::isRegularFile);

        assertEquals(gitSh.toString(), resolved.executable());
    }

    @Test
    void resolve_matchesPathVariableRegardlessOfCase(@TempDir Path dir) throws IOException {
        // Windows reports the variable as "Path" in some environments.
        Path bash = Files.createFile(dir.resolve("bash.exe"));

        ResolvedShell resolved =
                ShellResolver.resolve(
                        "bash", "Windows 11", Map.of("Path", dir.toString()), Files::isRegularFile);

        assertEquals(bash.toString(), resolved.executable());
    }

    @Test
    void resolve_reportsNameThatIsNeitherOnPathNorInstalled() {
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ShellResolver.resolve(
                                        "fish", "Linux", Map.of("PATH", ""), path -> false));

        assertTrue(
                thrown.getMessage().contains("fish"),
                "message should name the executable, was: " + thrown.getMessage());
    }

    @Test
    void resolve_passesCommandsToCmdExeWithSlashC(@TempDir Path dir) throws IOException {
        Path cmd = Files.createFile(dir.resolve("cmd.exe"));

        ResolvedShell resolved =
                ShellResolver.resolve(cmd.toString(), "Windows 11", Map.of(), Files::isRegularFile);

        assertEquals("/c", resolved.commandFlag());
    }

    @Test
    void resolve_passesCommandsToPowerShellWithCommandFlag(@TempDir Path dir) throws IOException {
        Path powershell = Files.createFile(dir.resolve("pwsh.exe"));

        ResolvedShell resolved =
                ShellResolver.resolve(
                        powershell.toString(), "Windows 11", Map.of(), Files::isRegularFile);

        assertEquals("-Command", resolved.commandFlag());
    }

    @Test
    void resolve_keepsCmdExeAsWindowsDefault() {
        // Switching the default would run every existing cmd command under a POSIX shell, so the
        // default stays the shell the host itself provides.
        ResolvedShell resolved = ShellResolver.resolve(null, "Windows 11", Map.of(), path -> false);

        assertEquals("cmd.exe", resolved.executable());
        assertEquals("/c", resolved.commandFlag());
    }

    @Test
    void resolve_usesShOnPosixHosts() {
        ResolvedShell resolved = ShellResolver.resolve(null, "Linux", Map.of(), path -> false);

        assertEquals("sh", resolved.executable());
        assertEquals("-c", resolved.commandFlag());
    }

    @Test
    void resolve_makesRelativeConfiguredPathAbsolute() {
        // ProcessBuilder resolves a relative program against the child's cwd, not the JVM's.
        ResolvedShell resolved =
                ShellResolver.resolve("tools/agentscope-test-shell", "Linux", Map.of(), p -> true);

        Path expected = Paths.get("tools/agentscope-test-shell").toAbsolutePath().normalize();
        assertTrue(expected.isAbsolute(), "test premise: a relative path becomes absolute");
        assertEquals(expected.toString(), resolved.executable());
    }

    @Test
    void resolve_rejectsConfiguredDirectory(@TempDir Path dir) {
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ShellResolver.resolve(
                                        dir.toString(), "Linux", Map.of(), Files::isRegularFile));

        assertTrue(
                thrown.getMessage().contains("directory"),
                "message should say it is a directory, was: " + thrown.getMessage());
    }

    @Test
    void resolve_rejectsConfiguredFileThatIsNotExecutable(@TempDir Path dir) throws IOException {
        // Windows has no executable bit, so there is nothing for this to assert there.
        assumeFalse(isWindowsHost(), "only POSIX has an executable bit to check");
        Path notExecutable = Files.createFile(dir.resolve("notes"));
        Files.setPosixFilePermissions(notExecutable, PosixFilePermissions.fromString("rw-r--r--"));

        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ShellResolver.resolve(notExecutable.toString(), Map.of()));

        assertTrue(
                thrown.getMessage().contains("not executable"),
                "message should say it is not executable, was: " + thrown.getMessage());
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
