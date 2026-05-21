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
package io.agentscope.spring.boot.responses.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.responses.builder.ResponsesResponseBuilder;
import io.agentscope.core.responses.converter.ResponsesGenerationOptionsConverter;
import io.agentscope.core.responses.converter.ResponsesInputConverter;
import io.agentscope.core.responses.converter.ResponsesToolConverter;
import io.agentscope.core.responses.model.ResponsesList;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesResponse;
import io.agentscope.core.responses.streaming.ResponsesStreamingAdapter;
import io.agentscope.spring.boot.responses.service.ResponsesStateService;
import io.agentscope.spring.boot.responses.service.ResponsesStreamingService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ResponsesControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ObjectProvider<ReActAgent> agentProvider;
    private ResponsesController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ResponsesToolConverter toolConverter = new ResponsesToolConverter();
        ResponsesResponseBuilder responseBuilder = new ResponsesResponseBuilder();
        ResponsesStreamingService streamingService =
                new ResponsesStreamingService(new ResponsesStreamingAdapter(responseBuilder));
        ResponsesStateService stateService = new ResponsesStateService();
        agentProvider = mock(ObjectProvider.class);
        controller =
                new ResponsesController(
                        agentProvider,
                        new ResponsesInputConverter(),
                        toolConverter,
                        new ResponsesGenerationOptionsConverter(toolConverter),
                        responseBuilder,
                        streamingService,
                        stateService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldStreamJsonSchemaFromJsonEndpoint() throws Exception {
        ReActAgent agent = prepareStructuredStreamingAgent();

        Object response = controller.createResponse(streamingJsonSchemaRequest());

        assertThat(response).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) response;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isInstanceOf(Flux.class);

        List<ServerSentEvent<String>> events =
                ((Flux<ServerSentEvent<String>>) entity.getBody()).collectList().block();
        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event).contains("response.completed");
        assertThat(events)
                .extracting(ServerSentEvent::event)
                .contains("response.output_text.delta");
        verify(agent).stream(anyList(), any(StreamOptions.class), any(JsonNode.class));
    }

    @Test
    void shouldReturnNotFoundForUnknownPreviousResponseIdThroughSpringMvc() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(
                        post("/v1/responses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "model": "gpt-4.1-mini",
                                          "previous_response_id": "resp_old",
                                          "input": "Hello"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("not_found"))
                .andExpect(jsonPath("$.error.param").value("response_id"));

        verifyNoInteractions(agentProvider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldStoreResponseAndUsePreviousResponseId() throws Exception {
        ReActAgent agent = prepareTextAgent("First answer", "Second answer");

        ResponsesResponse first =
                responseMono(
                                controller.createResponse(
                                        request(
                                                """
                                                {
                                                  "input": "Hello",
                                                  "store": true
                                                }
                                                """)))
                        .block();
        assertThat(first).isNotNull();
        assertThat(first.getStore()).isTrue();

        Object retrieved = controller.retrieveResponse(first.getId());
        assertThat(retrieved).isInstanceOf(ResponseEntity.class);
        assertThat(((ResponseEntity<?>) retrieved).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponsesResponse second =
                responseMono(
                                controller.createResponse(
                                        request(
                                                """
                                                {
                                                  "input": "Continue",
                                                  "previous_response_id": "%s"
                                                }
                                                """
                                                        .formatted(first.getId()))))
                        .block();

        assertThat(second).isNotNull();
        assertThat(second.getPreviousResponseId()).isEqualTo(first.getId());
        assertThat(second.getOutputText()).isEqualTo("Second answer");
        ArgumentCaptor<List<Msg>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(agent, times(2)).call(messagesCaptor.capture());
        assertThat(messagesCaptor.getAllValues().get(1).size())
                .isGreaterThan(messagesCaptor.getAllValues().get(0).size());
    }

    @Test
    void shouldCreateQueuedBackgroundResponse() throws Exception {
        prepareTextAgent("Background answer");

        ResponsesResponse response =
                responseMono(
                                controller.createResponse(
                                        request(
                                                """
                                                {
                                                  "input": "Run later",
                                                  "background": true
                                                }
                                                """)))
                        .block();

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("queued");
        assertThat(response.getBackground()).isTrue();
        assertThat(response.getStore()).isTrue();
        assertThat(controller.retrieveResponse(response.getId()))
                .isInstanceOf(ResponseEntity.class);
    }

    @Test
    void shouldReturnStoredResponseInputItems() throws Exception {
        prepareTextAgent("Stored");

        ResponsesResponse response =
                responseMono(
                                controller.createResponse(
                                        request(
                                                """
                                                {
                                                  "input": "Remember this",
                                                  "store": true
                                                }
                                                """)))
                        .block();

        Object result = controller.listResponseInputItems(response.getId(), null, null, null);
        assertThat(result).isInstanceOf(ResponseEntity.class);
        assertThat(((ResponseEntity<?>) result).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldCompactResponseInputThroughAgentCall() throws Exception {
        prepareTextAgent("Compacted answer");

        ResponsesResponse response =
                responseMono(
                                controller.compactResponseInput(
                                        request(
                                                """
                                                {
                                                  "input": "Summarize this context"
                                                }
                                                """)))
                        .block();

        assertThat(response).isNotNull();
        assertThat(response.getOutputText()).isEqualTo("Compacted answer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPageStoredResponseInputItems() throws Exception {
        prepareTextAgent("Stored");

        ResponsesResponse response =
                responseMono(
                                controller.createResponse(
                                        request(
                                                """
                                                {
                                                  "input": [
                                                    {"role": "user", "content": "First"},
                                                    {"role": "user", "content": "Second"}
                                                  ],
                                                  "store": true
                                                }
                                                """)))
                        .block();

        ResponseEntity<?> result =
                (ResponseEntity<?>)
                        controller.listResponseInputItems(response.getId(), null, 1, "asc");
        ResponsesList<Object> page = (ResponsesList<Object>) result.getBody();
        assertThat(page.getData()).hasSize(1);
        assertThat(page.isHasMore()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldStreamJsonSchemaFromSseEndpoint() throws Exception {
        ReActAgent agent = prepareStructuredStreamingAgent();

        Object response = controller.createResponseStream(streamingJsonSchemaRequest());

        assertThat(response).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) response;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isInstanceOf(Flux.class);

        List<ServerSentEvent<String>> events =
                ((Flux<ServerSentEvent<String>>) entity.getBody()).collectList().block();
        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event).contains("response.completed");
        assertThat(events)
                .extracting(ServerSentEvent::event)
                .contains("response.output_text.delta");
        verify(agent).stream(anyList(), any(StreamOptions.class), any(JsonNode.class));
    }

    private ReActAgent prepareStructuredStreamingAgent() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agentProvider.getObject()).thenReturn(agent);
        when(agent.getHooks()).thenReturn(new ArrayList<>());
        when(agent.stream(anyList(), any(StreamOptions.class), any(JsonNode.class)))
                .thenReturn(
                        Flux.just(new Event(EventType.AGENT_RESULT, structuredAssistant(), true)));
        return agent;
    }

    private ReActAgent prepareTextAgent(String... replies) {
        ReActAgent agent = mock(ReActAgent.class);
        when(agentProvider.getObject()).thenReturn(agent);
        when(agent.getHooks()).thenAnswer(invocation -> new ArrayList<>());
        @SuppressWarnings("unchecked")
        Mono<Msg>[] monos = new Mono[replies.length];
        for (int i = 0; i < replies.length; i++) {
            monos[i] = Mono.just(assistantText(replies[i]));
        }
        when(agent.call(anyList()))
                .thenReturn(monos[0], Arrays.copyOfRange(monos, 1, monos.length));
        return agent;
    }

    private Msg structuredAssistant() {
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("answer", "42");
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .metadata(Map.of(MessageMetadataKeys.STRUCTURED_OUTPUT, structuredOutput))
                .build();
    }

    private Msg assistantText(String text) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private ResponsesRequest streamingJsonSchemaRequest() throws Exception {
        return OBJECT_MAPPER.readValue(
                """
                {
                  "input": "Return JSON",
                  "stream": true,
                  "text": {
                    "format": {
                      "type": "json_schema",
                      "schema": {
                        "type": "object",
                        "properties": {
                          "answer": {"type": "string"}
                        },
                        "required": ["answer"]
                      }
                    }
                  }
                }
                """,
                ResponsesRequest.class);
    }

    private ResponsesRequest request(String json) throws Exception {
        return OBJECT_MAPPER.readValue(json, ResponsesRequest.class);
    }

    @SuppressWarnings("unchecked")
    private Mono<ResponsesResponse> responseMono(Object response) {
        return (Mono<ResponsesResponse>) response;
    }
}
