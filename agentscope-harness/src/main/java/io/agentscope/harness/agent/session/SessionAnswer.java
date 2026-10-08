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
