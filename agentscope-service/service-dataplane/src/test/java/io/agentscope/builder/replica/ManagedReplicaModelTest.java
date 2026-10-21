/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.builder.replica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

class ManagedReplicaModelTest {
    @Test
    void sendUsesTheOriginalSpawnKeyAndRequiresBothConfirmedChildResults() {
        try (var context = new GenericApplicationContext()) {
            new ManagedReplicaModel().initialize(context);
            context.refresh();
            var model = context.getBean("managedReplicaModel", Model.class);
            var messages =
                    new ArrayList<Msg>(List.of(new UserMessage("fixture:delegate-send:12:case")));
            var spawn =
                    (ToolUseBlock)
                            model.stream(messages, List.of(), null)
                                    .blockFirst()
                                    .getContent()
                                    .get(0);
            assertEquals("agent_spawn", spawn.getName());
            assertEquals("fixture:execute:12:child-one-case", spawn.getInput().get("task"));
            messages.add(
                    ToolResultMessage.builder()
                            .result(
                                    ToolResultBlock.text(
                                                    "agent_key: stable-key\n"
                                                        + "status: ok\n"
                                                        + "reply:\n"
                                                        + "harness-runtime-confirmed-hands-child-one-case")
                                            .withIdAndName(spawn.getId(), spawn.getName()))
                            .build());
            var send =
                    (ToolUseBlock)
                            model.stream(messages, List.of(), null)
                                    .blockFirst()
                                    .getContent()
                                    .get(0);
            assertEquals("agent_send", send.getName());
            assertEquals(
                    Map.of(
                            "agent_key",
                            "stable-key",
                            "message",
                            "fixture:execute:12:child-two-case",
                            "timeout_seconds",
                            0),
                    send.getInput());
            messages.add(
                    ToolResultMessage.builder()
                            .result(
                                    ToolResultBlock.text(
                                                    "harness-runtime-confirmed-hands-child-two-case")
                                            .withIdAndName(send.getId(), send.getName()))
                            .build());
            var result =
                    (TextBlock)
                            model.stream(messages, List.of(), null)
                                    .blockFirst()
                                    .getContent()
                                    .get(0);
            assertEquals("harness-runtime-confirmed-children-case", result.getText());
        }
    }

    @Test
    void aFailedChildCannotProduceAFalseSuccessfulParent() {
        try (var context = new GenericApplicationContext()) {
            new ManagedReplicaModel().initialize(context);
            context.refresh();
            var model = context.getBean("managedReplicaModel", Model.class);
            var messages =
                    List.<Msg>of(
                            new UserMessage("fixture:delegate:12:case"),
                            ToolResultMessage.builder()
                                    .result(
                                            ToolResultBlock.text(
                                                            "status: error\nerror: unavailable")
                                                    .withIdAndName(
                                                            "replica-spawn-case", "agent_spawn"))
                                    .build());
            assertThrows(
                    IllegalStateException.class,
                    () -> model.stream(messages, List.of(), null).blockFirst());
        }
    }
}
