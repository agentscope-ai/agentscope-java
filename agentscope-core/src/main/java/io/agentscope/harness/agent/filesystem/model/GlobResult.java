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
 * Result from abstract filesystem glob operations.
 *
 * @param error error message on failure, {@code null} on success
 * @param matches list of matching file info entries on success, {@code null} on failure
 */
public record GlobResult(String error, List<FileInfo> matches) {

    public static GlobResult success(List<FileInfo> matches) {
        return new GlobResult(null, matches);
    }

    public static GlobResult fail(String error) {
        return new GlobResult(error, null);
    }

    public boolean isSuccess() {
        return error == null;
    }
}
