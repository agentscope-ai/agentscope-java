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
package io.agentscope.extensions.sandbox.opensandbox;

import io.agentscope.harness.agent.sandbox.ExecResult;
import java.io.InputStream;

/** Package-private boundary that keeps the provider testable without a remote service. */
interface OpenSandboxSdk {

    Handle create(OpenSandboxState state, OpenSandboxClientOptions options) throws Exception;

    Handle connect(String sandboxId, OpenSandboxClientOptions options) throws Exception;

    void kill(String sandboxId, OpenSandboxClientOptions options) throws Exception;

    boolean isNotFound(Throwable error);

    interface Handle extends AutoCloseable {
        String id();

        ExecResult exec(String command, String workingDirectory, int timeoutSeconds)
                throws Exception;

        InputStream read(String absolutePath) throws Exception;

        void write(String absolutePath, byte[] content) throws Exception;
    }
}
