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
 * Structured file listing info. Only {@code path} is required;
 * other fields are best-effort and may be absent depending on filesystem implementation.
 *
 * @param path absolute or relative file path
 * @param isDirectory whether the entry is a directory
 * @param size file size in bytes (approximate)
 * @param modifiedAt ISO 8601 timestamp of last modification, or empty string if unknown
 */
public record FileInfo(String path, boolean isDirectory, long size, String modifiedAt) {

    public static FileInfo ofFile(String path, long size, String modifiedAt) {
        return new FileInfo(path, false, size, modifiedAt != null ? modifiedAt : "");
    }

    public static FileInfo ofDir(String path, String modifiedAt) {
        return new FileInfo(path, true, 0, modifiedAt != null ? modifiedAt : "");
    }

    public static FileInfo ofFile(String path, long size, long lastModifiedMs) {
        return new FileInfo(
                path, false, size, java.time.Instant.ofEpochMilli(lastModifiedMs).toString());
    }

    public static FileInfo ofDir(String path, long lastModifiedMs) {
        return new FileInfo(
                path, true, 0, java.time.Instant.ofEpochMilli(lastModifiedMs).toString());
    }
}
