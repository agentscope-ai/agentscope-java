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

import java.util.List;

/**
 * Result from abstract filesystem ls (directory listing) operations.
 *
 * @param error error message on failure, {@code null} on success
 * @param entries list of file info entries on success, {@code null} on failure
 */
public record LsResult(String error, List<FileInfo> entries) {

    public static LsResult success(List<FileInfo> entries) {
        return new LsResult(null, entries);
    }

    public static LsResult fail(String error) {
        return new LsResult(error, null);
    }

    public boolean isSuccess() {
        return error == null;
    }
}
