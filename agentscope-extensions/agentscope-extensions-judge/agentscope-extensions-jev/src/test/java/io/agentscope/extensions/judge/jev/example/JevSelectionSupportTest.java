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

package io.agentscope.extensions.judge.jev.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JevSelectionSupportTest {

    @Test
    void latestUserTextReturnsEmptyForNullMessages() {
        assertEquals("", JevSelectionSupport.latestUserText(null));
    }

    @Test
    void latestUserTextReturnsEmptyForEmptyMessages() {
        assertEquals("", JevSelectionSupport.latestUserText(List.of()));
    }

    @Test
    void latestUserTextReturnsEmptyWhenNoUserMessage() {
        Msg assistant = new Msg(null, null, MsgRole.ASSISTANT, List.of(), null, null, null) {};
        List<Msg> messages = new java.util.ArrayList<>();
        messages.add(null);
        messages.add(assistant);

        assertEquals("", JevSelectionSupport.latestUserText(messages));
    }

    @Test
    void latestUserTextReturnsEmptyWhenUserTextIsNull() {
        Msg nullText =
                new Msg(null, null, MsgRole.USER, List.of(), null, null, null) {
                    @Override
                    public String getTextContent() {
                        return null;
                    }
                };

        assertEquals("", JevSelectionSupport.latestUserText(List.of(nullText)));
    }

    @Test
    void latestUserTextTrimsWhitespace() {
        assertEquals(
                "hello", JevSelectionSupport.latestUserText(List.of(new UserMessage("  hello  "))));
    }

    @Test
    void messagesStateReturnsEmptyMessagesListForNullInput() {
        assertEquals(List.of(), JevSelectionSupport.messagesState(null).get("messages"));
    }

    @Test
    void messagesStateReturnsEmptyMessagesListForEmptyInput() {
        assertEquals(List.of(), JevSelectionSupport.messagesState(List.of()).get("messages"));
    }

    @Test
    void messagesStateSkipsNullElements() {
        List<Msg> messages = new java.util.ArrayList<>();
        messages.add(new UserMessage("one"));
        messages.add(null);
        messages.add(new UserMessage("two"));

        List<?> copied = (List<?>) JevSelectionSupport.messagesState(messages).get("messages");

        assertEquals(2, copied.size());
    }

    @Test
    void messagesStateCopiesEntireList() {
        List<Msg> messages = List.of(new UserMessage("one"), new UserMessage("two"));

        Map<String, Object> state = JevSelectionSupport.messagesState(messages);

        assertEquals(2, ((List<?>) state.get("messages")).size());
    }

    @Test
    void userRequestStateMapsNullToEmptyString() {
        assertEquals("", JevSelectionSupport.userRequestState(null).get("userRequest"));
    }

    @Test
    void userRequestStateKeepsText() {
        assertEquals("hi", JevSelectionSupport.userRequestState("hi").get("userRequest"));
    }

    @Test
    void recentWindowStateReturnsEmptyListStateForInvalidInput() {
        List<Msg> messages = List.of(new UserMessage("one"));

        assertEquals(
                List.of(), JevSelectionSupport.recentWindowState(null, 8, 100).get("messages"));
        assertEquals(
                List.of(),
                JevSelectionSupport.recentWindowState(List.of(), 8, 100).get("messages"));
        assertEquals(
                List.of(), JevSelectionSupport.recentWindowState(messages, 0, 100).get("messages"));
        assertEquals(
                List.of(), JevSelectionSupport.recentWindowState(messages, 8, 0).get("messages"));
    }

    @Test
    void recentWindowStateKeepsNewestMessagesWithinCount() {
        List<Msg> messages =
                List.of(new UserMessage("one"), new UserMessage("two"), new UserMessage("three"));

        List<?> entries =
                (List<?>) JevSelectionSupport.recentWindowState(messages, 2, 100).get("messages");

        assertEquals(2, entries.size());
        assertEquals("two", ((Map<?, ?>) entries.get(0)).get("text"));
        assertEquals("three", ((Map<?, ?>) entries.get(1)).get("text"));
    }

    @Test
    void recentWindowStateTruncatesOldestKeptMessageAtCharBudget() {
        List<Msg> messages = List.of(new UserMessage("aaaaaaaaaa"), new UserMessage("bbbbb"));

        List<?> entries =
                (List<?>) JevSelectionSupport.recentWindowState(messages, 8, 7).get("messages");

        assertEquals(2, entries.size());
        assertEquals("aa", ((Map<?, ?>) entries.get(0)).get("text"));
        assertEquals("bbbbb", ((Map<?, ?>) entries.get(1)).get("text"));
    }

    @Test
    void recentWindowStateDoesNotSplitSurrogatePairsWhenTruncating() {
        // "😀" occupies two UTF-16 units; a cut landing between them would produce an
        // unpaired high surrogate instead of a valid string.
        List<Msg> messages = List.of(new UserMessage("a😀b"));

        List<?> entries =
                (List<?>) JevSelectionSupport.recentWindowState(messages, 8, 2).get("messages");

        assertEquals(1, entries.size());
        assertEquals("a", ((Map<?, ?>) entries.get(0)).get("text"));
    }

    @Test
    void recentWindowStateSkipsEntriesTruncatedToEmpty() {
        // The newer message consumes all but one char of the budget; cutting the older
        // emoji-led message at that point would empty it after the surrogate backoff, so the
        // entry must be skipped entirely instead of occupying a window slot with blank text.
        List<Msg> messages = List.of(new UserMessage("😀ab"), new UserMessage("ccccc"));

        List<?> entries =
                (List<?>) JevSelectionSupport.recentWindowState(messages, 8, 6).get("messages");

        assertEquals(1, entries.size());
        assertEquals("ccccc", ((Map<?, ?>) entries.get(0)).get("text"));
    }

    @Test
    void recentWindowStateDropsMessagesBeyondCharBudget() {
        List<Msg> messages =
                List.of(
                        new UserMessage("aaaaaaaaaa"),
                        new UserMessage("bbbbb"),
                        new UserMessage("cc"));

        List<?> entries =
                (List<?>) JevSelectionSupport.recentWindowState(messages, 8, 7).get("messages");

        assertEquals(2, entries.size());
        assertEquals("bbbbb", ((Map<?, ?>) entries.get(0)).get("text"));
        assertEquals("cc", ((Map<?, ?>) entries.get(1)).get("text"));
    }

    @Test
    void recentWindowStateSkipsNullAndBlankTextMessages() {
        Msg nullText =
                new Msg(null, null, MsgRole.USER, List.of(), null, null, null) {
                    @Override
                    public String getTextContent() {
                        return null;
                    }
                };
        Msg blankText =
                new Msg(null, null, MsgRole.USER, List.of(), null, null, null) {
                    @Override
                    public String getTextContent() {
                        return "   ";
                    }
                };
        List<Msg> messages = new java.util.ArrayList<>();
        messages.add(null);
        messages.add(nullText);
        messages.add(blankText);
        messages.add(new UserMessage("real"));

        List<?> entries =
                (List<?>) JevSelectionSupport.recentWindowState(messages, 2, 100).get("messages");

        assertEquals(1, entries.size());
        assertEquals("real", ((Map<?, ?>) entries.get(0)).get("text"));
    }

    @Test
    void recentWindowStateBackfillsPastBlankMessages() {
        Msg blank =
                new Msg(null, null, MsgRole.USER, List.of(), null, null, null) {
                    @Override
                    public String getTextContent() {
                        return "   ";
                    }
                };
        List<Msg> messages = List.of(new UserMessage("realA"), blank, new UserMessage("realB"));

        List<?> entries =
                (List<?>) JevSelectionSupport.recentWindowState(messages, 2, 100).get("messages");

        assertEquals(2, entries.size());
        assertEquals("realA", ((Map<?, ?>) entries.get(0)).get("text"));
        assertEquals("realB", ((Map<?, ?>) entries.get(1)).get("text"));
    }

    @Test
    void recentWindowStateLowercasesRoles() {
        Msg assistant =
                new Msg(null, null, MsgRole.ASSISTANT, List.of(), null, null, null) {
                    @Override
                    public String getTextContent() {
                        return "hi";
                    }
                };

        List<?> entries =
                (List<?>)
                        JevSelectionSupport.recentWindowState(List.of(assistant), 8, 100)
                                .get("messages");

        assertEquals("assistant", ((Map<?, ?>) entries.get(0)).get("role"));
        assertEquals("hi", ((Map<?, ?>) entries.get(0)).get("text"));
    }

    @Test
    void partitionReturnsEmptyForNullAndEmpty() {
        assertTrue(JevSelectionSupport.partition(null, 2).isEmpty());
        assertTrue(JevSelectionSupport.partition(List.of(), 2).isEmpty());
    }

    @Test
    void partitionSplitsWithRemainder() {
        List<List<Integer>> partitions = JevSelectionSupport.partition(List.of(1, 2, 3, 4, 5), 2);

        assertEquals(3, partitions.size());
        assertEquals(List.of(1, 2), partitions.get(0));
        assertEquals(List.of(3, 4), partitions.get(1));
        assertEquals(List.of(5), partitions.get(2));
    }

    @Test
    void toolCriteriaIncludesToolsAndNoneOption() {
        ToolSchema tool = ToolSchema.builder().name("search").description("Search the web").build();

        Map<String, Object> criteria = JevSelectionSupport.toolCriteria(List.of(tool));

        assertEquals("Search the web", criteria.get("search"));
        assertTrue(criteria.containsKey(JevSelectionSupport.NONE_OPTION));
    }

    @Test
    void selectedNamesReturnsEmptyForNullAnswer() {
        assertTrue(JevSelectionSupport.selectedNames(null, 3, 0.5).isEmpty());
    }

    @Test
    void selectedNamesReturnsEmptyForNullProbabilities() {
        assertTrue(
                JevSelectionSupport.selectedNames(new ChoiceAnswer("a", null, 0.9), 3, 0.5)
                        .isEmpty());
    }

    @Test
    void selectedNamesReturnsEmptyForEmptyProbabilities() {
        assertTrue(
                JevSelectionSupport.selectedNames(new ChoiceAnswer("a", Map.of(), 0.9), 3, 0.5)
                        .isEmpty());
    }

    @Test
    void selectedNamesReturnsEmptyForNullConfidence() {
        assertTrue(
                JevSelectionSupport.selectedNames(
                                new ChoiceAnswer("a", Map.of("a", 1.0), null), 3, 0.5)
                        .isEmpty());
    }

    @Test
    void selectedNamesReturnsEmptyBelowConfidenceThreshold() {
        ChoiceAnswer answer = new ChoiceAnswer("a", Map.of("a", 1.0), 0.4);

        assertTrue(JevSelectionSupport.selectedNames(answer, 3, 0.5).isEmpty());
    }

    @Test
    void selectedNamesFiltersNoneEqualToNoneAndNullValues() {
        Map<String, Double> probabilities = new HashMap<>();
        probabilities.put("search", 0.7);
        probabilities.put("read", 0.1);
        probabilities.put("write", null);
        probabilities.put(JevSelectionSupport.NONE_OPTION, 0.1);
        ChoiceAnswer answer = new ChoiceAnswer("search", probabilities, 0.9);

        assertEquals(List.of("search"), JevSelectionSupport.selectedNames(answer, 3, 0.5));
    }

    @Test
    void selectedNamesSortsDescendingAndAppliesLimit() {
        ChoiceAnswer answer =
                new ChoiceAnswer(
                        "b",
                        Map.of(
                                "a",
                                0.3,
                                "b",
                                0.5,
                                "c",
                                0.15,
                                JevSelectionSupport.NONE_OPTION,
                                0.05),
                        0.9);

        assertEquals(List.of("b", "a"), JevSelectionSupport.selectedNames(answer, 2, 0.5));
    }

    @Test
    void topNameReturnsNullForNullAnswer() {
        assertNull(JevSelectionSupport.topName(null));
    }

    @Test
    void topNameReturnsNullForNullProbabilities() {
        assertNull(JevSelectionSupport.topName(new ChoiceAnswer("a", null, 0.9)));
    }

    @Test
    void topNameReturnsNullForEmptyProbabilities() {
        assertNull(JevSelectionSupport.topName(new ChoiceAnswer("a", Map.of(), 0.9)));
    }

    @Test
    void topNameReturnsNullWhenNoneHasHighestProbability() {
        ChoiceAnswer answer =
                new ChoiceAnswer(
                        JevSelectionSupport.NONE_OPTION,
                        Map.of("search", 0.1, JevSelectionSupport.NONE_OPTION, 0.9),
                        0.9);

        assertNull(JevSelectionSupport.topName(answer));
    }

    @Test
    void topNameReturnsNullWhenAllValuesAreNull() {
        Map<String, Double> probabilities = new HashMap<>();
        probabilities.put("search", null);
        probabilities.put(JevSelectionSupport.NONE_OPTION, 0.0);

        assertNull(JevSelectionSupport.topName(new ChoiceAnswer("search", probabilities, 0.9)));
    }

    @Test
    void topNamePicksHighestProbabilityAboveNone() {
        ChoiceAnswer answer =
                new ChoiceAnswer(
                        "b", Map.of("a", 0.2, "b", 0.6, JevSelectionSupport.NONE_OPTION, 0.2), 0.9);

        assertEquals("b", JevSelectionSupport.topName(answer));
    }

    @Test
    void topNameExcludesValuesEqualToNone() {
        ChoiceAnswer answer =
                new ChoiceAnswer("a", Map.of("a", 0.1, JevSelectionSupport.NONE_OPTION, 0.1), 0.9);

        assertNull(JevSelectionSupport.topName(answer));
    }
}
