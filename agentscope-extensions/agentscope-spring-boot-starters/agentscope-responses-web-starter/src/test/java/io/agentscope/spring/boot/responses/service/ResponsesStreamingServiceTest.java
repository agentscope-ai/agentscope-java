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
package io.agentscope.spring.boot.responses.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.responses.builder.ResponsesResponseBuilder;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesResponse;
import io.agentscope.core.responses.model.ResponsesStreamEvent;
import io.agentscope.core.responses.streaming.ResponsesStreamingAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

class ResponsesStreamingServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldEmitResponseFailedWhenSseSerializationFails() throws Exception {
        ResponsesStreamingAdapter streamingAdapter = mock(ResponsesStreamingAdapter.class);
        ResponsesStreamingAdapter realAdapter =
                new ResponsesStreamingAdapter(new ResponsesResponseBuilder());
        ResponsesStreamingService service = new ResponsesStreamingService(streamingAdapter);
        ReActAgent agent = mock(ReActAgent.class);
        List<Msg> messages = List.of();
        ResponsesRequest request = new ResponsesRequest();
        request.setModel("test-model");
        String responseId = "resp_test";

        when(streamingAdapter.stream(agent, messages, request, responseId))
                .thenReturn(
                        Flux.just(unserializableEvent(responseId), serializableEvent(responseId)));
        when(streamingAdapter.createFailedEvent(any(Throwable.class), eq(request), eq(responseId)))
                .thenAnswer(
                        invocation ->
                                realAdapter.createFailedEvent(
                                        invocation.getArgument(0), request, responseId));

        List<ServerSentEvent<String>> events =
                service.streamAsSse(agent, messages, request, responseId).collectList().block();

        assertThat(events).hasSize(1);
        ServerSentEvent<String> event = events.get(0);
        assertThat(event).isNotNull();
        assertThat(event.event()).isEqualTo("response.failed");
        JsonNode data = OBJECT_MAPPER.readTree(event.data());
        assertThat(data.path("type").asText()).isEqualTo("response.failed");
        assertThat(data.path("response").path("id").asText()).isEqualTo(responseId);
        assertThat(data.path("response").path("status").asText()).isEqualTo("failed");
        assertThat(data.path("response").path("error").path("code").asText())
                .isEqualTo("runtime_error");
    }

    private ResponsesStreamEvent unserializableEvent(String responseId) {
        ResponsesResponse response = new ResponsesResponse();
        response.setId(responseId);
        response.setStatus("in_progress");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("self", metadata);
        response.setMetadata(metadata);
        return ResponsesStreamEvent.responseEvent("response.in_progress", response);
    }

    private ResponsesStreamEvent serializableEvent(String responseId) {
        ResponsesResponse response = new ResponsesResponse();
        response.setId(responseId);
        response.setStatus("completed");
        return ResponsesStreamEvent.responseEvent("response.completed", response);
    }
}
