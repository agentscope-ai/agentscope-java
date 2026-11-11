/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.skill.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.skill.util.MarkdownSkillParser.ParsedMarkdown;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarkdownSkillParserAliasTest {
    @ParameterizedTest
    @ValueSource(strings = {"&m {self: *m}", "&m [*m]", "&m {items: [*m]}"})
    void recursiveMetadataAliasesBecomeNullLeaves(String metadata) {
        ParsedMarkdown parsed =
                MarkdownSkillParser.parse("---\nname: demo\nmetadata: " + metadata + "\n---\nbody");
        assertEquals("demo", parsed.getMetadata().get("name"));
        assertEquals("body", parsed.getContent());
        Object value = parsed.getMetadata().get("metadata");
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("self")) {
                assertNull(map.get("self"));
            } else {
                assertNull(((List<?>) map.get("items")).get(0));
            }
        } else {
            assertNull(((List<?>) value).get(0));
        }
    }

    @Test
    void sharedNonrecursiveAliasIsPreservedInEachBranch() {
        ParsedMarkdown parsed =
                MarkdownSkillParser.parse(
                        "---\nname: demo\nfirst: &m {value: 42}\nsecond: *m\n---\nbody");
        assertEquals(Map.of("value", 42), parsed.getMetadata().get("first"));
        assertEquals(Map.of("value", 42), parsed.getMetadata().get("second"));
    }
}
