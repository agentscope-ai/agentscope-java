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

import io.agentscope.core.responses.builder.ResponsesResponseBuilder;
import io.agentscope.core.responses.model.ResponsesConversation;
import io.agentscope.core.responses.model.ResponsesConversationItemsRequest;
import io.agentscope.core.responses.model.ResponsesConversationRequest;
import io.agentscope.spring.boot.responses.service.ResponsesStateService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ConversationsControllerTest {

    private ConversationsController controller;

    @BeforeEach
    void setUp() {
        controller =
                new ConversationsController(
                        new ResponsesStateService(), new ResponsesResponseBuilder());
    }

    @Test
    void shouldCreateRetrieveUpdateAndDeleteConversation() {
        ResponsesConversationRequest request = new ResponsesConversationRequest();
        request.setMetadata(Map.of("user", "alice"));

        ResponseEntity<?> createdEntity =
                (ResponseEntity<?>) controller.createConversation(request);
        assertThat(createdEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponsesConversation created = (ResponsesConversation) createdEntity.getBody();
        assertThat(created.getId()).startsWith("conv_");

        ResponseEntity<?> retrieved =
                (ResponseEntity<?>) controller.retrieveConversation(created.getId());
        assertThat(retrieved.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponsesConversationRequest update = new ResponsesConversationRequest();
        update.setMetadata(Map.of("user", "bob"));
        ResponseEntity<?> updated =
                (ResponseEntity<?>) controller.updateConversation(created.getId(), update);
        assertThat(((ResponsesConversation) updated.getBody()).getMetadata())
                .containsEntry("user", "bob");

        ResponseEntity<?> deleted =
                (ResponseEntity<?>) controller.deleteConversation(created.getId());
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldCreateRetrieveListAndDeleteConversationItems() {
        ResponsesConversation created =
                (ResponsesConversation)
                        ((ResponseEntity<?>) controller.createConversation(null)).getBody();
        ResponsesConversationItemsRequest itemRequest = new ResponsesConversationItemsRequest();
        itemRequest.setItems(
                List.of(Map.of("type", "message", "role", "user", "content", "Hello")));

        ResponseEntity<?> createdItems =
                (ResponseEntity<?>)
                        controller.createConversationItems(created.getId(), itemRequest);
        assertThat(createdItems.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<?> list =
                (ResponseEntity<?>)
                        controller.listConversationItems(created.getId(), null, null, null);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        Map<String, Object> item =
                (Map<String, Object>)
                        ((io.agentscope.core.responses.model.ResponsesList<?>)
                                        createdItems.getBody())
                                .getData()
                                .get(0);
        ResponseEntity<?> retrieved =
                (ResponseEntity<?>)
                        controller.retrieveConversationItem(
                                created.getId(), (String) item.get("id"));
        assertThat(retrieved.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<?> deleted =
                (ResponseEntity<?>)
                        controller.deleteConversationItem(created.getId(), (String) item.get("id"));
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
