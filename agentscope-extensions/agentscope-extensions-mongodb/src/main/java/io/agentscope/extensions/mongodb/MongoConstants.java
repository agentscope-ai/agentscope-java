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
package io.agentscope.extensions.mongodb;

/**
 * Default database and collection names for MongoDB distributed store components.
 *
 * <p>Centralised here so that all four component classes reference the same source of truth,
 * preventing drift when the same collection name is referenced from multiple construction paths.
 */
public final class MongoConstants {

    private MongoConstants() {}

    /** Default database name used when no explicit name is supplied. */
    public static final String DEFAULT_DATABASE = "agentscope";

    /** Collection holding agent session state documents ({@code MongoAgentStateStore}). */
    public static final String SESSIONS_COLLECTION = "agentscope_sessions";

    /** Collection holding workspace filesystem key-value items ({@code MongoBaseStore}). */
    public static final String BASE_STORE_COLLECTION = "agentscope_base";

    /**
     * GridFS bucket (and legacy single-document collection) holding sandbox snapshots
     * ({@code MongoRemoteSnapshotClient}).
     */
    public static final String SNAPSHOTS_COLLECTION = "agentscope_snapshots";

    /** Collection holding sandbox execution lease locks ({@code MongoSandboxExecutionGuard}). */
    public static final String SANDBOX_LOCKS_COLLECTION = "agentscope_sandbox_locks";
}
