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
package io.agentscope.examples.quarkus;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST resource demonstrating AgentScope integration with Quarkus.
 *
 * <p>Example requests:
 * <pre>
 * # Simple chat
 * curl -X POST http://localhost:8080/agent/chat \
 *   -H "Content-Type: application/json" \
 *   -d '{"message":"Hello, who are you?"}'
 *
 * # Health check
 * curl http://localhost:8080/agent/health
 * </pre>
 */
@Path("/agent")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AgentResource {

    /**
     * Injected ReActAgent - automatically configured from application.properties.
     */
    @Inject ReActAgent agent;

    /**
     * Chat endpoint - sends a message to the agent and returns the response.
     *
     * @param request the chat request containing the user message
     * @return the agent's response
     */
    @POST
    @Path("/chat")
    public Response chat(ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Message cannot be empty"))
                    .build();
        }

        try {
            // Create user message
            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(request.message()).build())
                            .build();

            // Call agent and get response
            Msg response = agent.call(userMsg).block();

            // Extract text content from response
            String responseText = response != null ? response.getTextContent() : "No response";

            return Response.ok(new ChatResponse(responseText)).build();

        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(
                            new ErrorResponse(
                                    "An internal error occurred. Please try again later."))
                    .build();
        }
    }

    /**
     * Health check endpoint.
     *
     * @return health status
     */
    @GET
    @Path("/health")
    public Response health() {
        return Response.ok(new HealthResponse("AgentScope agent is ready", agent.getName()))
                .build();
    }

    /**
     * Chat request DTO.
     */
    public record ChatRequest(String message) {}

    /**
     * Chat response DTO.
     */
    public record ChatResponse(String response) {}

    /**
     * Error response DTO.
     */
    public record ErrorResponse(String error) {}

    /**
     * Health response DTO.
     */
    public record HealthResponse(String status, String agentName) {}
}
