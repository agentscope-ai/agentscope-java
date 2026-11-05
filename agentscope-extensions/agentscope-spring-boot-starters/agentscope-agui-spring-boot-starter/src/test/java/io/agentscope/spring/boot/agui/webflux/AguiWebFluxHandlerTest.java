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
package io.agentscope.spring.boot.agui.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

class AguiWebFluxHandlerTest {

    @Test
    void errorEventStreamUsesFallbackForNullMessage() {
        AguiWebFluxHandler handler =
                AguiWebFluxHandler.builder().agentRegistry(new AguiAgentRegistry()).build();

        Flux<ServerSentEvent<String>> events =
                ReflectionTestUtils.invokeMethod(
                        handler, "createErrorEventStream", "thread-1", "run-1", null);
        List<ServerSentEvent<String>> sentEvents = events.collectList().block();

        assertEquals(2, sentEvents.size());
        assertTrue(sentEvents.get(0).data().contains("\"error\":\"Unknown error\""));
        assertTrue(sentEvents.get(1).data().contains("\"type\":\"RUN_FINISHED\""));
    }
}
