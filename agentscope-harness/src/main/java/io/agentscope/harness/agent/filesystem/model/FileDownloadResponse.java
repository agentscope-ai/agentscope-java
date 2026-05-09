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
 * Result of a single file download operation.
 *
 * @param path the file path that was requested
 * @param content file contents as bytes on success, {@code null} on failure
 * @param error error description on failure, {@code null} on success
 */
public record FileDownloadResponse(String path, byte[] content, String error) {

    public static FileDownloadResponse success(String path, byte[] content) {
        return new FileDownloadResponse(path, content, null);
    }

    public static FileDownloadResponse fail(String path, String error) {
        return new FileDownloadResponse(path, null, error);
    }

    public boolean isSuccess() {
        return error == null;
    }
}
