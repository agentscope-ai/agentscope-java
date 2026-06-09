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
package io.agentscope.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot of a file's contents kept in the tool read cache.
 *
 * @param lines line contents at the time of capture (defensively copied; never {@code null})
 * @param updatedAt file modification timestamp in epoch seconds with millisecond fraction
 * @param bytes approximate size of the cached entry in kilobytes (UTF-8 byte sum / 1024)
 * @param filePath absolute path to the cached file (never {@code null})
 */
public record ReadCacheEntry(
        @JsonProperty("lines") List<String> lines,
        @JsonProperty("updated_at") double updatedAt,
        @JsonProperty("bytes") double bytes,
        @JsonProperty("file_path") String filePath) {

    @JsonCreator
    public ReadCacheEntry {
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        lines = List.copyOf(lines);
    }
}
