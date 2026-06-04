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
package io.agentscope.dataagent.web.marketplace;

/**
 * One file's content + relative path inside a contribution payload.
 *
 * <p>A contribution's payload is always a list of {@code FileEntry}s, persisted as JSON in
 * {@code dataagent_contribution.payload}. The list contains exactly one entry for single-file
 * target types (subagent / memory / agents_md / knowledge), and one or more entries for
 * multi-file skill bundles (SKILL.md plus side resources).
 *
 * <p>{@code relPath} is interpreted relative to the target type's shared directory — e.g. for
 * {@code targetType=skill} and {@code targetPath="cohort-builder"}, an entry with
 * {@code relPath="scripts/run.py"} lands at
 * {@code shared/agents/<agentId>/skills/cohort-builder/scripts/run.py}. An empty {@code relPath}
 * is the default file (e.g. {@code SKILL.md} for skills, the target file itself for everything
 * else).
 */
public record FileEntry(String relPath, String content) {
    public FileEntry {
        if (relPath == null) {
            relPath = "";
        }
        if (content == null) {
            content = "";
        }
    }
}
