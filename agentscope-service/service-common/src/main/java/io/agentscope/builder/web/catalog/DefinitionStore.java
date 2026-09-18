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
package io.agentscope.builder.web.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Control-plane persistence for agent <em>definition</em> files (skills, optional workspace
 * assets, staged resources), independent of the session Hands {@code AbstractFilesystem}.
 *
 * <p>Structured config ({@code tools[]} / {@code mcpServers[]} / skill refs) remains in the DB
 * version snapshot; this store holds file payloads so any control-plane replica can build a
 * {@code HarnessAgent} without local disk affinity.
 */
public interface DefinitionStore {

    /** Writes or replaces a UTF-8 text file under the agent definition namespace. */
    void putText(String ownerId, String agentId, String relativePath, String content);

    /** Reads a UTF-8 text file, or empty when missing. */
    Optional<String> getText(String ownerId, String agentId, String relativePath);

    /** Deletes one file key. Missing keys are ignored. */
    void delete(String ownerId, String agentId, String relativePath);

    /**
     * Deletes every file whose relative path equals {@code prefix} or starts with {@code
     * prefix + "/"}.
     */
    void deletePrefix(String ownerId, String agentId, String prefix);

    /**
     * Lists relative paths under {@code prefix} (inclusive of nested files). When {@code prefix}
     * is blank, lists the entire agent definition tree.
     */
    List<String> list(String ownerId, String agentId, String prefix);
}
