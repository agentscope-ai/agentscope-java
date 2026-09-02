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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AbstractBaseFormatter prompt cache tests")
class AbstractBaseFormatterPromptCacheTest {

    private final TestFormatter formatter = new TestFormatter();

    @Test
    void selectsFirstSystemAndLastCacheableConversationItemAutomatically() {
        CacheItem firstSystem = new CacheItem("system-1", false, true, true);
        CacheItem secondSystem = new CacheItem("system-2", false, true, true);
        CacheItem user = new CacheItem("user", false, false, true);
        CacheItem emptyAssistant = new CacheItem("assistant-empty", false, false, false);

        List<CacheItem> selected =
                formatter.select(List.of(firstSystem, secondSystem, user, emptyAssistant), true);

        assertEquals(List.of(firstSystem, user), selected);
    }

    @Test
    void keepsExplicitBreakpointsAndPreservesRequestOrder() {
        CacheItem system = new CacheItem("system", false, true, true);
        CacheItem explicitUser = new CacheItem("user", true, false, true);
        CacheItem assistant = new CacheItem("assistant", false, false, true);
        CacheItem explicitTool = new CacheItem("tool", true, false, true);

        List<CacheItem> selected =
                formatter.select(List.of(system, explicitUser, assistant, explicitTool), true);

        assertEquals(List.of(system, explicitUser, explicitTool), selected);
    }

    @Test
    void explicitBreakpointsConsumeCapacityBeforeAutomaticBreakpoints() {
        CacheItem system = new CacheItem("system", false, true, true);
        CacheItem explicitOne = new CacheItem("one", true, false, true);
        CacheItem explicitTwo = new CacheItem("two", true, false, true);
        CacheItem explicitThree = new CacheItem("three", true, false, true);
        CacheItem last = new CacheItem("last", false, false, true);

        List<CacheItem> selected =
                formatter.select(
                        List.of(system, explicitOne, explicitTwo, explicitThree, last), true);

        assertEquals(List.of(system, explicitOne, explicitTwo, explicitThree), selected);
    }

    @Test
    void disablingAutomaticSelectionKeepsOnlyExplicitBreakpoints() {
        CacheItem system = new CacheItem("system", false, true, true);
        CacheItem explicit = new CacheItem("explicit", true, false, true);
        CacheItem last = new CacheItem("last", false, false, true);

        assertEquals(List.of(explicit), formatter.select(List.of(system, explicit, last), false));
    }

    @Test
    void rejectsMoreThanFourExplicitBreakpoints() {
        List<CacheItem> items =
                List.of(
                        new CacheItem("one", true, false, true),
                        new CacheItem("two", true, false, true),
                        new CacheItem("three", true, false, true),
                        new CacheItem("four", true, false, true),
                        new CacheItem("five", true, false, true));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> formatter.select(items, true));

        assertTrue(error.getMessage().contains("at most 4 explicit breakpoints"));
    }

    @Test
    void returnsEmptySelectionForNullOrEmptyInput() {
        assertTrue(formatter.select(null, true).isEmpty());
        assertTrue(formatter.select(List.of(), true).isEmpty());
    }

    @Test
    void explicitCacheControlPreservesMultiAgentMessageBoundary() {
        Msg cached =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .textContent("cache me")
                        .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, true))
                        .build();
        Msg notCached =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .textContent("merge me")
                        .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, false))
                        .build();

        assertTrue(formatter.bypassesHistory(cached));
        assertTrue(formatter.bypassesHistory(notCached));
    }

    private record CacheItem(String name, boolean explicit, boolean system, boolean cacheable) {}

    private static final class TestFormatter extends AbstractBaseFormatter<String, String, String> {

        List<CacheItem> select(List<CacheItem> items, boolean automatic) {
            return selectPromptCacheBreakpoints(
                    items, automatic, CacheItem::explicit, CacheItem::system, CacheItem::cacheable);
        }

        boolean bypassesHistory(Msg msg) {
            return shouldBypassHistory(msg);
        }

        @Override
        protected List<String> doFormat(List<Msg> msgs) {
            return List.of();
        }

        @Override
        public ChatResponse parseResponse(String response, Instant startTime) {
            return null;
        }

        @Override
        public void applyOptions(
                String paramsBuilder, GenerateOptions options, GenerateOptions defaultOptions) {}

        @Override
        public void applyTools(String paramsBuilder, List<ToolSchema> tools) {}
    }
}
