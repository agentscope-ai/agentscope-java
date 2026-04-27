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
 * Error codes for sandbox operations, used in {@link SandboxException} and its subclasses.
 */
public enum SandboxErrorCode {

    /** Command exited with a non-zero exit code. */
    EXEC_NONZERO,

    /** Command execution timed out. */
    EXEC_TIMEOUT,

    /** Failed to start the workspace backend. */
    WORKSPACE_START_ERROR,

    /** Failed to stop/persist the workspace backend. */
    WORKSPACE_STOP_ERROR,

    /** Failed to read or parse a workspace archive (tar). */
    WORKSPACE_ARCHIVE_READ_ERROR,

    /** Failed to create a workspace archive (tar). */
    WORKSPACE_ARCHIVE_WRITE_ERROR,

    /** Failed to persist a snapshot. */
    SNAPSHOT_PERSIST_ERROR,

    /** Failed to restore a snapshot. */
    SNAPSHOT_RESTORE_ERROR,

    /** Snapshot does not exist or is not restorable. */
    SNAPSHOT_NOT_RESTORABLE,

    /** A manifest entry contains an invalid or unsafe path. */
    INVALID_MANIFEST_PATH,

    /** Invalid or missing sandbox configuration. */
    CONFIGURATION_ERROR
}
