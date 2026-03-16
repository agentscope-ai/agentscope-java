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
package io.agentscope.core.skill;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

public class SkillHook implements Hook {
    private final SkillBox skillBox;

    public SkillHook(SkillBox skillBox) {
        this.skillBox = skillBox;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // Inject skill prompts
        if (event instanceof PreReasoningEvent preReasoningEvent) {
            String skillPrompt = skillBox.getSkillPrompt();
            if (skillPrompt != null && !skillPrompt.isEmpty()) {
                List<Msg> inputMessages = preReasoningEvent.getInputMessages();
                int systemIndex = findFirstSystemMessageIndex(inputMessages);
                if (systemIndex >= 0) {
                    // Merge skill prompt into existing system message in-place (structural)
                    Msg existingSystem = inputMessages.get(systemIndex);
                    List<ContentBlock> mergedContent = new ArrayList<>(existingSystem.getContent());
                    mergedContent.add(TextBlock.builder().text(skillPrompt).build());
                    Msg mergedMsg =
                            Msg.builder()
                                    .id(existingSystem.getId())
                                    .role(MsgRole.SYSTEM)
                                    .name(existingSystem.getName())
                                    .content(mergedContent)
                                    .metadata(existingSystem.getMetadata())
                                    .timestamp(existingSystem.getTimestamp())
                                    .build();
                    List<Msg> newMessages = new ArrayList<>(inputMessages);
                    newMessages.set(systemIndex, mergedMsg);
                    preReasoningEvent.setInputMessages(newMessages);
                } else {
                    // No existing system message, add one at the beginning
                    List<Msg> newMessages = new ArrayList<>(inputMessages.size() + 1);
                    newMessages.add(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(TextBlock.builder().text(skillPrompt).build())
                                    .build());
                    newMessages.addAll(inputMessages);
                    preReasoningEvent.setInputMessages(newMessages);
                }
            }
            return Mono.just(event);
        }

        return Mono.just(event);
    }

    private int findFirstSystemMessageIndex(List<Msg> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getRole() == MsgRole.SYSTEM) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int priority() {
        // High priority (55) to ensure skills system prompt is added early
        // before other hooks that might depend on skill system prompt
        return 55;
    }
}
