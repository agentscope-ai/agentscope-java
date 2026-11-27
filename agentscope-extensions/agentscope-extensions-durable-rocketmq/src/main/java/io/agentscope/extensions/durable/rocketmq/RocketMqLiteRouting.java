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
package io.agentscope.extensions.durable.rocketmq;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.apis.message.MessageView;

final class RocketMqLiteRouting {
    private RocketMqLiteRouting() {}

    static String session(SessionKey session) {
        return key(session.namespace(), session.sessionId());
    }

    static String thread(ThreadKey thread) {
        return key(thread.session().namespace(), thread.session().sessionId(), thread.threadId());
    }

    static String owner(String runnerId) {
        return key("turn-owner", runnerId);
    }

    static String execution(ExecutionRequest execution) {
        return key(
                execution.session().namespace(),
                execution.session().sessionId(),
                execution.executionId());
    }

    static void require(MessageView message, String route, String key) {
        if (!message.getLiteTopic().filter(route::equals).isPresent())
            throw new IllegalArgumentException("RocketMQ LiteTopic differs from message scope");
        if (!message.getKeys().contains(key))
            throw new IllegalArgumentException(
                    "RocketMQ message key differs from payload identity");
    }

    private static String key(String... fields) {
        var scope = new StringBuilder();
        for (String field : fields)
            scope.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field);
        return DurableJson.hash(scope.toString());
    }
}
