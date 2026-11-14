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

import io.agentscope.harness.agent.filesystem.ShellSyntax;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Resolves the shell that {@link LocalFilesystemWithShell} launches for {@code execute()}.
 *
 * <p>A configured shell may be a path or a bare name; a name is looked up on {@code PATH}, and on
 * Windows also in the Git for Windows install directories. Windows defaults to {@code cmd.exe}
 * because it has no {@code sh} (#1396).
 */
final class ShellResolver {

    private static final String POSIX_FLAG = "-c";
    private static final String CMD_FLAG = "/c";
    private static final String POWERSHELL_FLAG = "-Command";

    private static final String POSIX_SHELL = "sh";
    private static final String CMD_SHELL = "cmd.exe";
    private static final String EXE_SUFFIX = ".exe";

    private ShellResolver() {}

    /** A shell plus the flag that hands it a whole command string. */
    record ResolvedShell(String executable, String commandFlag, ShellSyntax syntax) {

        List<String> commandLine(String command) {
            if (syntax == ShellSyntax.POWERSHELL) {
                // Redirected PowerShell output otherwise inherits the host console code page.
                command =
                        "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); "
                                + command;
            }
            return List.of(executable, commandFlag, command);
        }

        String description() {
            return executable + " " + commandFlag;
        }

        Charset outputCharset(String osName, String nativeEncoding) {
            if (!isWindows(osName) || syntax != ShellSyntax.CMD) {
                return StandardCharsets.UTF_8;
            }
            return nativeEncoding != null && Charset.isSupported(nativeEncoding)
                    ? Charset.forName(nativeEncoding)
                    : Charset.defaultCharset();
        }
    }

    /**
     * Resolves the shell for this host.
     *
     * @param configuredShellPath configured path or name; {@code null} or blank keeps the host default
     * @param environment environment consulted for {@code PATH} and the Git install roots
     * @return the shell to launch
     * @throws IllegalArgumentException when a configured shell cannot be found
     */
    static ResolvedShell resolve(String configuredShellPath, Map<String, String> environment) {
        return resolve(
                configuredShellPath,
                System.getProperty("os.name", ""),
                environment,
                // POSIX needs the executable bit; Windows has none, so there it only rules out
                // directories.
                path -> Files.isRegularFile(path) && Files.isExecutable(path));
    }

    /** Same as {@link #resolve(String, Map)} for an explicit host, so tests can reach both branches. */
    static ResolvedShell resolve(
            String configuredShellPath,
            String osName,
            Map<String, String> environment,
            Predicate<Path> exists) {
        boolean windows = isWindows(osName);
        String configured = trimToNull(configuredShellPath);
        if (configured == null) {
            // Left as a bare name: the JVM resolves it against the parent's PATH, so a caller that
            // replaces the child environment cannot leave the shell unlaunchable.
            return windows
                    ? new ResolvedShell(CMD_SHELL, CMD_FLAG, ShellSyntax.CMD)
                    : new ResolvedShell(POSIX_SHELL, POSIX_FLAG, ShellSyntax.POSIX);
        }
        Path located = locate(unquote(configured), windows, environment, exists);
        return shellFor(located.toString());
    }

    static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Turns a configured shell into an absolute path, because {@link ProcessBuilder} resolves a
     * relative program against the child's working directory rather than the JVM's.
     */
    private static Path locate(
            String shell,
            boolean windows,
            Map<String, String> environment,
            Predicate<Path> exists) {
        Path asPath = pathOrNull(shell);
        if (asPath != null && isPathLike(shell, windows)) {
            Path absolute = asPath.toAbsolutePath().normalize();
            if (Files.isDirectory(absolute)) {
                throw new IllegalArgumentException(
                        "Configured shell is a directory, not an executable: " + shell);
            }
            if (!exists.test(absolute)) {
                throw new IllegalArgumentException(
                        "Configured shell is missing or not executable: " + shell);
            }
            return absolute;
        }
        for (String name : candidateNames(shell, windows)) {
            for (Path directory : lookupDirectories(environment, windows)) {
                Path candidate = directory.resolve(name).toAbsolutePath().normalize();
                if (exists.test(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException(
                "Configured shell was not found on PATH: "
                        + shell
                        + " (set shellPath to a full path to the executable)");
    }

    private static List<String> candidateNames(String shell, boolean windows) {
        if (!windows || shell.toLowerCase(Locale.ROOT).endsWith(EXE_SUFFIX)) {
            return List.of(shell);
        }
        return List.of(shell + EXE_SUFFIX, shell);
    }

    /** {@code PATH}, then the Git for Windows installs. */
    private static List<Path> lookupDirectories(Map<String, String> environment, boolean windows) {
        List<Path> directories = new ArrayList<>();
        String path = envValue(environment, "PATH", windows);
        if (path != null && !path.isBlank()) {
            for (String entry : path.split(windows ? ";" : ":")) {
                String directory = unquote(entry.trim());
                // One unreadable entry must not stop the search, and an empty one is not the cwd.
                Path asPath = directory.isEmpty() ? null : pathOrNull(directory);
                if (asPath != null) {
                    directories.add(asPath);
                }
            }
        }
        if (windows) {
            for (String root : gitInstallRoots(environment)) {
                directories.add(Paths.get(root, "bin"));
                directories.add(Paths.get(root, "usr", "bin"));
            }
        }
        return directories;
    }

    private static List<String> gitInstallRoots(Map<String, String> environment) {
        List<String> roots = new ArrayList<>();
        for (String variable : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")) {
            String base = envValue(environment, variable, true);
            if (base != null && !base.isBlank()) {
                roots.add(Paths.get(unquote(base), "Git").toString());
            }
        }
        String localAppData = envValue(environment, "LOCALAPPDATA", true);
        if (localAppData != null && !localAppData.isBlank()) {
            roots.add(Paths.get(unquote(localAppData), "Programs", "Git").toString());
        }
        return roots;
    }

    /** Case-insensitive on Windows only, where the environment itself is. */
    private static String envValue(
            Map<String, String> environment, String name, boolean caseInsensitive) {
        String direct = environment.get(name);
        if (direct != null || !caseInsensitive) {
            return direct;
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Strips the quotes Windows users put around paths containing spaces. */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Path pathOrNull(String shell) {
        try {
            return Paths.get(shell);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Decides whether the value names a location rather than an executable on {@code PATH}. */
    private static boolean isPathLike(String shell, boolean windows) {
        if (shell.indexOf('/') >= 0 || shell.indexOf('\\') >= 0) {
            return true;
        }
        // "C:bash" is a drive-relative path, not an executable name.
        return windows && shell.length() > 1 && shell.charAt(1) == ':';
    }

    /** Picks the flag and family that match the executable's own name. */
    private static ResolvedShell shellFor(String executable) {
        String name = fileName(executable).toLowerCase(Locale.ROOT);
        if (name.equals("cmd") || name.equals("cmd.exe")) {
            return new ResolvedShell(executable, CMD_FLAG, ShellSyntax.CMD);
        }
        if (name.startsWith("powershell") || name.equals("pwsh") || name.equals("pwsh.exe")) {
            return new ResolvedShell(executable, POWERSHELL_FLAG, ShellSyntax.POWERSHELL);
        }
        return new ResolvedShell(executable, POSIX_FLAG, ShellSyntax.POSIX);
    }

    /** Last segment of a path, tolerating both separators because the host may not match. */
    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
