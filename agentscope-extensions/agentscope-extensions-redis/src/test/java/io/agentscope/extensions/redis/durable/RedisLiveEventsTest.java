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
package io.agentscope.extensions.redis.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.lettuce.core.api.sync.BaseRedisCommands;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RedisLiveEventsTest {
    @Test
    @SuppressWarnings("unchecked")
    void aPublishedPreviewCanReachEveryApiConnection() throws Exception {
        String channel = "durable-preview-test";
        BaseRedisCommands<String, String> producer = mock(BaseRedisCommands.class);
        StatefulRedisPubSubConnection<String, String> first =
                mock(StatefulRedisPubSubConnection.class);
        StatefulRedisPubSubConnection<String, String> second =
                mock(StatefulRedisPubSubConnection.class);
        RedisPubSubCommands<String, String> sync = mock(RedisPubSubCommands.class);
        RedisPubSubAsyncCommands<String, String> async = mock(RedisPubSubAsyncCommands.class);
        when(first.sync()).thenReturn(sync);
        when(second.sync()).thenReturn(sync);
        when(first.async()).thenReturn(async);
        when(second.async()).thenReturn(async);
        var firstInbox = new LinkedBlockingQueue<LiveEvent>();
        var secondInbox = new LinkedBlockingQueue<LiveEvent>();
        try (var brain = new RedisLiveEvents(channel, producer, null);
                var api1 = new RedisLiveEvents(channel, null, first);
                var api2 = new RedisLiveEvents(channel, null, second);
                var observe1 = api1.subscribe(firstInbox::add);
                var observe2 = api2.subscribe(secondInbox::add)) {
            ArgumentCaptor<RedisPubSubListener<String, String>> added =
                    ArgumentCaptor.forClass(RedisPubSubListener.class);
            verify(first).addListener(added.capture());
            verify(second).addListener(added.capture());
            when(producer.publish(eq(channel), anyString()))
                    .thenAnswer(
                            invocation -> {
                                for (var observer : added.getAllValues())
                                    observer.message(channel, invocation.getArgument(1));
                                return 2L;
                            });
            var preview =
                    new LiveEvent(
                            "1",
                            "message",
                            new SessionKey("tenant", "session"),
                            "main",
                            "turn",
                            "run",
                            "step",
                            1,
                            "message.delta",
                            DurableJson.value(Map.of("text", "你好")));
            brain.publish(preview);
            assertEquals(preview, firstInbox.poll(3, TimeUnit.SECONDS));
            assertEquals(preview, secondInbox.poll(3, TimeUnit.SECONDS));
            assertTrue(firstInbox.isEmpty());
            assertTrue(secondInbox.isEmpty());
        }
        verify(first, never()).close();
        verify(second, never()).close();
    }
}
