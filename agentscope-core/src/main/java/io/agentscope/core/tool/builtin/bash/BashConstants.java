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
package io.agentscope.core.tool.builtin.bash;

import java.util.List;
import java.util.Set;

/**
 * Canonical safe and dangerous command sets consumed by {@link BashCommandParser}.
 *
 * <p>The lists are conservative: pure inspection commands belong in {@link #SAFE_COMMANDS}, common
 * read-only git subcommands in {@link #GIT_READ_ONLY_COMMANDS}. {@link #DANGEROUS_COMMANDS} holds
 * regex/substring fragments matched against the literal command text.
 */
public final class BashConstants {

    private BashConstants() {
        // utility constants
    }

    /** Commands considered read-only system-wide; subject to optional argument heuristics. */
    public static final Set<String> SAFE_COMMANDS =
            Set.of(
                    "ls",
                    "cat",
                    "head",
                    "tail",
                    "wc",
                    "grep",
                    "rg",
                    "find",
                    "fd",
                    "pwd",
                    "echo",
                    "printf",
                    "which",
                    "type",
                    "whoami",
                    "id",
                    "uname",
                    "date",
                    "env",
                    "printenv",
                    "df",
                    "du",
                    "ps",
                    "top",
                    "stat",
                    "file",
                    "tree",
                    "diff",
                    "cmp",
                    "sort",
                    "uniq",
                    "tr",
                    "cut",
                    "awk",
                    "less",
                    "more",
                    "sed",
                    "true",
                    "false",
                    "test");

    /** Git subcommands that do not mutate the working tree or remote. */
    public static final Set<String> GIT_READ_ONLY_COMMANDS =
            Set.of(
                    "status",
                    "log",
                    "diff",
                    "show",
                    "blame",
                    "branch",
                    "tag",
                    "remote",
                    "config",
                    "rev-parse",
                    "ls-files",
                    "ls-tree",
                    "describe",
                    "shortlog",
                    "reflog",
                    "stash",
                    "fetch",
                    "for-each-ref",
                    "cat-file",
                    "grep");

    /** Environment variables that may be set inline without flagging the call as risky. */
    public static final Set<String> SAFE_ENV_VARS =
            Set.of(
                    "PATH",
                    "HOME",
                    "USER",
                    "PWD",
                    "LANG",
                    "LC_ALL",
                    "LC_CTYPE",
                    "TERM",
                    "SHELL",
                    "DISPLAY",
                    "EDITOR",
                    "PAGER",
                    "TMPDIR",
                    "TZ");

    /** Filesystem-modifying shell commands granted by {@code accept_edits} mode. */
    public static final Set<String> ACCEPT_EDITS_FILESYSTEM_COMMANDS =
            Set.of("mkdir", "touch", "rm", "rmdir", "mv", "cp", "sed", "ln");

    /**
     * Tree-sitter node types whose presence indicates dynamic expansion or non-linear control
     * flow; these warrant a manual safety prompt.
     */
    public static final Set<String> DANGEROUS_NODE_TYPES =
            Set.of(
                    "command_substitution",
                    "process_substitution",
                    "for_statement",
                    "while_statement",
                    "if_statement",
                    "case_statement",
                    "function_definition");

    /** Patterns matched against literal command text; first match wins. */
    public static final List<String> DANGEROUS_COMMANDS =
            List.of(
                    "rm -rf /",
                    "rm -rf /*",
                    "rm -rf ~",
                    "rm -rf $HOME",
                    ":(){:|:&};:",
                    "dd if=/dev/zero",
                    "dd if=/dev/random",
                    "mkfs",
                    "fdisk",
                    "format",
                    "chmod 777",
                    "chmod -R 777",
                    "shutdown",
                    "reboot",
                    "halt",
                    "poweroff",
                    "curl | sh",
                    "curl | bash",
                    "wget | sh",
                    "wget | bash",
                    "eval ",
                    "exec ");

    /** Critical system paths whose removal warrants escalation. */
    public static final Set<String> CRITICAL_REMOVAL_PATHS =
            Set.of(
                    "/", "~", "*", "/usr", "/etc", "/var", "/bin", "/sbin", "/boot", "/lib",
                    "/lib64", "/opt", "/root", "/sys", "/proc", "/dev", "/tmp");
}
