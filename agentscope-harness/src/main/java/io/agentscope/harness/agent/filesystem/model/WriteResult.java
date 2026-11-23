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
 * Result of a file write operation.
 *
 * @param path absolute path of written file, {@code null} on failure
 * @param error error message on failure, {@code null} on success
 * @param alreadyExists whether an exclusive write failed because the path already exists
 */
public record WriteResult(String path, String error, boolean alreadyExists) {

    private static final String ALREADY_EXISTS_PREFIX = "Cannot write to ";
    private static final String ALREADY_EXISTS_SUFFIX =
            " because it already exists. Read and then make an edit, or write to a new path.";

    public WriteResult(String path, String error) {
        this(path, error, false);
    }

    public static WriteResult ok(String path) {
        return new WriteResult(path, null);
    }

    public static WriteResult fail(String error) {
        return new WriteResult(null, error);
    }

    /** Creates the shared create-if-absent conflict result used by filesystem backends. */
    public static WriteResult alreadyExists(String path) {
        return new WriteResult(null, ALREADY_EXISTS_PREFIX + path + ALREADY_EXISTS_SUFFIX, true);
    }

    public boolean isSuccess() {
        return error == null;
    }

    /** Recognizes a create-if-absent conflict independently of a routed backend's path. */
    public boolean isAlreadyExists() {
        return alreadyExists;
    }
}
