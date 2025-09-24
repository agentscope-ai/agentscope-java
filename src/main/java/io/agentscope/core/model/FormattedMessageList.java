/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FormattedMessageList implements Iterable<FormattedMessage> {
    private final List<FormattedMessage> messages;

    public FormattedMessageList(List<Map<String, Object>> rawMessages) {
        this.messages =
                rawMessages.stream().map(FormattedMessage::new).collect(Collectors.toList());
    }

    public FormattedMessageList(List<FormattedMessage> messages, boolean isFormattedMessages) {
        this.messages = new ArrayList<>(messages);
    }

    public FormattedMessage get(int index) {
        return messages.get(index);
    }

    public int size() {
        return messages.size();
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public List<Map<String, Object>> asMaps() {
        return messages.stream().map(FormattedMessage::asMap).collect(Collectors.toList());
    }

    /**
     * Filter messages by role.
     */
    public FormattedMessageList filterByRole(String role) {
        List<FormattedMessage> filtered =
                messages.stream().filter(msg -> msg.isRole(role)).collect(Collectors.toList());
        return new FormattedMessageList(filtered, true);
    }

    /**
     * Get only messages with tool calls.
     */
    public FormattedMessageList getMessagesWithToolCalls() {
        List<FormattedMessage> filtered =
                messages.stream()
                        .filter(FormattedMessage::hasToolCalls)
                        .collect(Collectors.toList());
        return new FormattedMessageList(filtered, true);
    }

    /**
     * Add a message to this list and return a new FormattedMessageList.
     */
    public FormattedMessageList add(FormattedMessage message) {
        List<FormattedMessage> newMessages = new ArrayList<>(messages);
        newMessages.add(message);
        return new FormattedMessageList(newMessages, true);
    }

    /**
     * Add all messages from another list and return a new FormattedMessageList.
     */
    public FormattedMessageList addAll(FormattedMessageList other) {
        List<FormattedMessage> newMessages = new ArrayList<>(messages);
        newMessages.addAll(other.messages);
        return new FormattedMessageList(newMessages, true);
    }

    @Override
    public Iterator<FormattedMessage> iterator() {
        return messages.iterator();
    }

    public List<FormattedMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    @Override
    public String toString() {
        return "FormattedMessageList{" + "size=" + messages.size() + ", messages=" + messages + '}';
    }

    public static FormattedMessageList empty() {
        return new FormattedMessageList(new ArrayList<>(), true);
    }

    public static FormattedMessageList of(FormattedMessage... messages) {
        List<FormattedMessage> messageList = new ArrayList<>();
        for (FormattedMessage message : messages) {
            messageList.add(message);
        }
        return new FormattedMessageList(messageList, true);
    }
}
