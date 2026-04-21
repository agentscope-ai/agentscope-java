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
package io.agentscope.harness.agent.filesystem.model;

/**
 * Result of code/shell execution.
 *
 * @param output combined stdout and stderr output of the executed command
 * @param exitCode the process exit code (0 indicates success, non-zero indicates failure)
 * @param truncated whether the output was truncated due to filesystem limitations
 */
public record ExecuteResponse(String output, Integer exitCode, boolean truncated) {

    public boolean isSuccess() {
        return exitCode != null && exitCode == 0;
    }
}
