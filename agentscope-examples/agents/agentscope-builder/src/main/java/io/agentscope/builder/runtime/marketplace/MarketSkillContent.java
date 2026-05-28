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
package io.agentscope.builder.runtime.marketplace;

import java.util.Map;

/**
 * Full skill payload returned by {@link BuilderMarketplace#fetch(String)}. {@code markdown} is the
 * SKILL.md body; {@code resources} are sibling files keyed by their workspace-relative path
 * (e.g. {@code "templates/intro.md"} → contents).
 *
 * @param name        skill identifier; matches the {@link MarketSkillSummary#name()} requested
 * @param description one-line summary mirrored from the summary so callers don't need a second lookup
 * @param markdown    SKILL.md body; must be present and non-empty
 * @param resources   relative-path → file contents for every side file; never null, may be empty
 */
public record MarketSkillContent(
        String name, String description, String markdown, Map<String, String> resources) {}
