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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

/**
 * Shell family behind {@code execute()}, for callers that build command strings or prompts.
 *
 * <p>It comes from the filesystem that will run the command, not from {@code os.name}, because a
 * Windows host can be configured to run commands through a POSIX shell.
 */
public enum ShellSyntax {

    /** {@code sh}, {@code bash}, or another POSIX shell, invoked with {@code -c}. */
    POSIX,

    /** {@code cmd.exe}, invoked with {@code /c}. */
    CMD,

    /** PowerShell, invoked with {@code -Command}. */
    POWERSHELL;

    /**
     * Reports the syntax of the filesystem that will run the command. A wrapper that delegates
     * {@code execute()} reports the backend's syntax itself, so only sandbox filesystems are asked.
     *
     * @param filesystem filesystem the command runs on
     * @return shell syntax used by that filesystem
     */
    public static ShellSyntax of(AbstractFilesystem filesystem) {
        if (filesystem instanceof AbstractSandboxFilesystem sandbox) {
            return sandbox.shellSyntax();
        }
        return POSIX;
    }
}
