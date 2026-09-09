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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchMatchModeTest {

    @Test
    void defaultsToPhraseAndTreatsQueryLiterally() {
        assertTrue(SearchMatchMode.parse(null).matches("deploy [blue]", "deploy [blue]"));
        assertFalse(SearchMatchMode.parse(null).matches("deploy blue", "deploy [blue]"));
    }

    @Test
    void allMatchesEveryKeywordWithinOneRecord() {
        assertTrue(SearchMatchMode.ALL.matches("Deploy using Blue Whale", "deploy whale"));
        assertFalse(SearchMatchMode.ALL.matches("Deploy using Blue", "deploy whale"));
    }

    @Test
    void anyMatchesAtLeastOneKeywordWithinOneRecord() {
        assertTrue(SearchMatchMode.ANY.matches("port is 9580", "deploy 9580"));
        assertFalse(SearchMatchMode.ANY.matches("port is 9580", "deploy whale"));
    }

    @Test
    void blankAndUnknownModesPreservePhraseSemantics() {
        assertTrue(SearchMatchMode.parse(" ").matches("a b", "a b"));
        assertTrue(SearchMatchMode.parse("future").matches("a b", "a b"));
        assertFalse(SearchMatchMode.parse("future").matches("a b", "a c"));
    }
}
