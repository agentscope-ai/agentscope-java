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

import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;

/**
 * Filesystem abstraction that adds shell command execution (sandbox or remote host).
 *
 * <p>Extends {@link AbstractFilesystem} with {@link #execute} and {@link #id()}.
 */
public interface AbstractSandboxFilesystem extends AbstractFilesystem {

    /**
     * Unique identifier for this filesystem/sandbox instance.
     *
     * @return id string
     */
    String id();

    /**
     * Execute a shell command in the environment backing this filesystem.
     *
     * @param command full shell command string to execute
     * @param timeoutSeconds maximum time in seconds to wait for the command to complete;
     *                       {@code null} uses the filesystem's default timeout
     * @return ExecuteResponse with combined output, exit code, and truncation flag
     */
    ExecuteResponse execute(String command, Integer timeoutSeconds);
}
