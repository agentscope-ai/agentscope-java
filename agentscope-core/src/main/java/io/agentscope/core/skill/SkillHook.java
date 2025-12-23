package io.agentscope.core.skill;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
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
        // Reset skill state and skill tool group before and after calls
        if (event instanceof PreCallEvent preCallEvent) {
            skillBox.deactivateAllSkills();
            skillBox.syncToolGroupStates();
            return Mono.just(event);
        }

        if (event instanceof PostCallEvent postCallEvent) {
            skillBox.deactivateAllSkills();
            skillBox.syncToolGroupStates();
            return Mono.just(event);
        }

        // Inject skill prompts
        if (event instanceof PreReasoningEvent preReasoningEvent) {
            skillBox.syncToolGroupStates();
            String skillPrompt = skillBox.getSkillPrompt();
            if (skillPrompt != null && !skillPrompt.isEmpty()) {
                List<Msg> inputMessages = new ArrayList<>(preReasoningEvent.getInputMessages());
                inputMessages.add(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text(skillPrompt).build())
                                .build());
                preReasoningEvent.setInputMessages(inputMessages);
            }
            return Mono.just(event);
        }

        return Mono.just(event);
    }

    @Override
    public int priority() {
        // High priority (10) to ensure skills system prompt is added early
        // before other hooks that might depend on skill system prompt
        return 10;
    }
}
