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
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Injects the skill catalog prompt into the first system message on {@link PreCallEvent}.
 *
 * <p>Uses priority {@link #SKILL_HOOK_PRIORITY} so that, in typical {@code HarnessAgent} wiring,
 * this hook runs after {@code SubagentsHook} (80) and before {@code WorkspaceContextHook} (900),
 * yielding append order: base prompt → subagents → skills → workspace context.
 */
public class SkillHook implements Hook {

    /**
     * Runs after subagent prompt injection and before workspace context injection in the default
     * harness hook chain.
     */
    public static final int SKILL_HOOK_PRIORITY = 85;

    private final SkillBox skillBox;

    public SkillHook(SkillBox skillBox) {
        this.skillBox = skillBox;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            String skillPrompt = skillBox.getSkillPrompt();
            if (skillPrompt != null && !skillPrompt.isEmpty()) {
                List<Msg> inputMessages = preCallEvent.getInputMessages();
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
                    preCallEvent.setInputMessages(newMessages);
                } else {
                    // No existing system message, add one at the beginning
                    List<Msg> newMessages = new ArrayList<>(inputMessages.size() + 1);
                    newMessages.add(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(TextBlock.builder().text(skillPrompt).build())
                                    .build());
                    newMessages.addAll(inputMessages);
                    preCallEvent.setInputMessages(newMessages);
                }
            }
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
        return SKILL_HOOK_PRIORITY;
    }
}
