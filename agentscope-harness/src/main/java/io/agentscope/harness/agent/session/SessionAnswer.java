package io.agentscope.harness.agent.session;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.List;

/** Typed reply to a pending interaction. The session supplies the authoritative tool identity. */
public sealed interface SessionAnswer {
    record Output(List<ContentBlock> content) implements SessionAnswer {
        public Output {
            content = List.copyOf(content);
        }
    }

    record Approval(boolean approved, String reason) implements SessionAnswer {}

    record Confirmation(ConfirmResult result) implements SessionAnswer {}

    static SessionAnswer text(String text) {
        return new Output(List.of(TextBlock.builder().text(text).build()));
    }

    static SessionAnswer approve() {
        return new Approval(true, null);
    }

    static SessionAnswer reject(String reason) {
        return new Approval(false, reason);
    }
}
