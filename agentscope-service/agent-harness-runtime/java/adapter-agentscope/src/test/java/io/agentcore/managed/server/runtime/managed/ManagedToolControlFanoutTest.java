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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class ManagedToolControlFanoutTest {
    @Test
    void broadcastFailureDoesNotUndoDurableCancellationOrSuppressLocalOwner() throws Exception {
        var redis = mock(RedisManagedInfrastructure.class);
        var subscription = mock(AutoCloseable.class);
        when(redis.busListen(anyString(), any())).thenReturn(subscription);
        doThrow(new ManagedTransportException("offline"))
                .when(redis)
                .busPublish(anyString(), anyString());
        @SuppressWarnings("unchecked")
        BiConsumer<ManagedScope, String> local = mock(BiConsumer.class);
        var scope = new ManagedScope("t", "n", "s");
        try (var fanout = new ManagedToolControlFanout(redis, new ObjectMapper(), local)) {
            fanout.wakeup(scope, "task");
        }
        verify(local).accept(scope, "task");
        verify(subscription).close();
    }
}
