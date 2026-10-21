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

package io.agentscope.extensions.judge.jev.evidence;

import java.util.Map;
import java.util.Objects;

/** Immutable, versioned retrieval evidence. Attributes are preserved but not sent to the judge. */
public record JevPassage(String id, String text, String version, Map<String, String> attributes) {
    public JevPassage(String id, String text, String version) {
        this(id, text, version, Map.of());
    }

    public JevPassage {
        if (id == null
                || id.isBlank()
                || id.length() > 512
                || version == null
                || version.isBlank()
                || version.length() > 512)
            throw new IllegalArgumentException("bounded passage id/version required");
        Objects.requireNonNull(text);
        attributes = Map.copyOf(attributes);
    }
}
