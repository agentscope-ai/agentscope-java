/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import io.lettuce.core.ScriptOutputType;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/** A one-result Redis Stream, backed and repaired by immutable ToolTask facts. */
public final class RedisManagedToolResultDelivery implements ManagedToolResultDelivery {
    private static final String PUBLISH =
            """
            local old = redis.call('GET', KEYS[2])
            if old and old ~= ARGV[1] then return -1 end
            if not old or redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('XADD', KEYS[1], 'MAXLEN', '=', 1, '*', 'id', ARGV[2], 'body', ARGV[3])
                redis.call('SET', KEYS[2], ARGV[1])
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            return 1
            """;
    private final RedisManagedInfrastructure redis;

    public RedisManagedToolResultDelivery(RedisManagedInfrastructure redis) {
        this.redis = redis;
    }

    @Override
    public void publish(ManagedScope scope, String taskId, Message message) {
        if (!scope.messageGroup().equals(message.messageGroup()))
            throw new IllegalArgumentException("Tool result message has another Scope");
        String encoded = Base64.getEncoder().encodeToString(message.body());
        String key = ManagedToolResultDelivery.key(scope, taskId);
        Long result =
                redis.busCommands()
                        .eval(
                                PUBLISH,
                                ScriptOutputType.INTEGER,
                                new String[] {key, key + ":identity"},
                                ManagedScope.fingerprint(message.id(), encoded),
                                message.id(),
                                encoded,
                                Long.toString(Duration.ofMinutes(5).toMillis()));
        if (result != null && result == -1)
            throw new ManagedStateException(
                    "tool_result_conflict", "Mailbox result identity was reused");
    }

    @Override
    public Optional<Message> await(ManagedScope scope, String taskId, Duration block) {
        var entries =
                redis.readStream(ManagedToolResultDelivery.key(scope, taskId), "0-0", 1, block);
        if (entries.isEmpty()) return Optional.empty();
        var body = entries.get(0).getBody();
        try {
            return Optional.of(
                    new Message(
                            body.get("id"),
                            scope.messageGroup(),
                            Base64.getDecoder().decode(body.get("body"))));
        } catch (RuntimeException corrupt) {
            throw new ManagedTransportException("Tool result mailbox is corrupt", corrupt);
        }
    }
}
