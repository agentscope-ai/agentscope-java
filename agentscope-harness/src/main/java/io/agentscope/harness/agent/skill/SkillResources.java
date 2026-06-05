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
package io.agentscope.harness.agent.skill;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Lazy resource accessor for a single skill.
 *
 * <p>Decouples "what resources exist" from "how to fetch their contents", so that repositories
 * backed by an {@code AbstractFilesystem} can answer {@code load_skill_through_path} calls
 * on demand without preloading every byte of every skill into memory at registration time.
 *
 * <p>All paths are relative to the skill root (e.g. {@code "references/guide.md"},
 * {@code "scripts/run.py"}). Absolute paths and path traversal sequences ({@code ".."}) are
 * rejected by implementations.
 */
public interface SkillResources {

    /**
     * Reads a text resource as UTF-8.
     *
     * @param relativePath path relative to the skill root
     * @return content, or empty if the resource does not exist or read fails
     */
    Optional<String> read(String relativePath);

    /**
     * Reads a binary resource.
     *
     * @param relativePath path relative to the skill root
     * @return content, or empty if the resource does not exist or read fails
     */
    Optional<byte[]> readBinary(String relativePath);

    /**
     * Lists all relative resource paths that this accessor can serve.
     *
     * <p>Used by {@code SkillLoadTool} to build a friendly "available resources" enumeration
     * when a requested path is not found.
     *
     * @return unmodifiable list of relative paths; never {@code null}
     */
    List<String> list();

    /**
     * Returns a no-op accessor that reports no resources.
     *
     * @return shared empty instance
     */
    static SkillResources empty() {
        return EmptySkillResources.INSTANCE;
    }
}

final class EmptySkillResources implements SkillResources {

    static final EmptySkillResources INSTANCE = new EmptySkillResources();

    private EmptySkillResources() {}

    @Override
    public Optional<String> read(String relativePath) {
        return Optional.empty();
    }

    @Override
    public Optional<byte[]> readBinary(String relativePath) {
        return Optional.empty();
    }

    @Override
    public List<String> list() {
        return Collections.emptyList();
    }
}
