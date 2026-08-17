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
package io.agentscope.core.permission;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * A directory included in a permission context's working scope.
 *
 * <p>Working directories drive the auto-allow behaviour of {@link PermissionMode#ACCEPT_EDITS}
 * for file-touching tools.
 *
 * @param path absolute directory path (never {@code null})
 * @param source provenance (e.g. {@code "userSettings"}, {@code "session"})
 */
public record AdditionalWorkingDirectory(
        @JsonProperty("path") String path, @JsonProperty("source") String source) {

    @JsonCreator
    public AdditionalWorkingDirectory {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
