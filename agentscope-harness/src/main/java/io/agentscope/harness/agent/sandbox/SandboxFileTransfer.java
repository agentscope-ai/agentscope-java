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
 * Optional capability for {@link Sandbox} backends that support native (non-exec) file
 * transfer, e.g. a dedicated HTTP upload/download API exposed by the sandbox runtime.
 *
 * <p>When a sandbox implements this interface, {@link
 * io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem} prefers it over the
 * default transfer strategy of embedding base64 payloads in shell commands sent through
 * {@link Sandbox#exec}. Native transfer avoids base64 inflation and per-argument command-line
 * length limits (about 128 KiB per argument on Linux), which the exec strategy hits for large
 * files.
 *
 * <p>Backends typically support native transfer only for a subtree of the sandbox filesystem
 * (e.g. paths under the runtime file API base directory); {@link #supportsFileTransfer} lets
 * the backend accept or decline each path, with declined paths handled by the exec strategy.
 */
public interface SandboxFileTransfer {

    /**
     * Whether this sandbox can natively transfer the given path.
     *
     * @param absolutePath absolute path inside the sandbox
     * @return true if {@link #uploadFile} / {@link #downloadFile} accept this path
     */
    boolean supportsFileTransfer(String absolutePath);

    /**
     * Writes a file inside the sandbox, creating parent directories if needed.
     *
     * @param absolutePath absolute destination path inside the sandbox
     * @param content file bytes
     * @throws Exception when the transfer fails
     */
    void uploadFile(String absolutePath, byte[] content) throws Exception;

    /**
     * Reads a file from the sandbox.
     *
     * @param absolutePath absolute path inside the sandbox
     * @return file bytes
     * @throws Exception when the transfer fails
     */
    byte[] downloadFile(String absolutePath) throws Exception;
}
