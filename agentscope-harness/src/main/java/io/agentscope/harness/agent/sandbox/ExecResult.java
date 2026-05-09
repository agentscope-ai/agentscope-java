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
package io.agentscope.harness.agent.sandbox;

/**
 * Result of a sandbox command execution.
 *
 * @param exitCode process exit code (0 indicates success)
 * @param stdout captured standard output
 * @param stderr captured standard error
 * @param truncated whether output was truncated due to exceeding the maximum capture size
 */
public record ExecResult(int exitCode, String stdout, String stderr, boolean truncated) {

    /**
     * Returns {@code true} if the command exited with code 0.
     *
     * @return true if exit code is 0
     */
    public boolean ok() {
        return exitCode == 0;
    }

    /**
     * Returns combined stdout and stderr, with stderr prefixed with "[stderr]" if non-empty.
     *
     * @return combined output string
     */
    public String combinedOutput() {
        if (stderr == null || stderr.isBlank()) {
            return stdout != null ? stdout : "";
        }
        String out = stdout != null ? stdout : "";
        return out + (out.isBlank() ? "" : "\n") + "[stderr] " + stderr;
    }
}
