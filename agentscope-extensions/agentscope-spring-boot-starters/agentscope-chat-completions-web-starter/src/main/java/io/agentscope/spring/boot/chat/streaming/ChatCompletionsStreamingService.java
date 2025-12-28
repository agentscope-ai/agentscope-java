package io.agentscope.spring.boot.chat.streaming;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.spring.boot.chat.builder.ChatCompletionsResponseBuilder;
import java.util.List;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Service for handling streaming chat completion responses.
 *
 * <p>This service converts agent events to Server-Sent Events (SSE) format for streaming
 * responses.
 *
 * <p>This component is automatically discovered by Spring Boot's component scanning.
 */
@Component
public class ChatCompletionsStreamingService {

    private final ChatCompletionsResponseBuilder responseBuilder;

    /**
     * Constructs a new ChatCompletionsStreamingService.
     *
     * @param responseBuilder Builder for extracting text content from messages
     */
    public ChatCompletionsStreamingService(ChatCompletionsResponseBuilder responseBuilder) {
        this.responseBuilder = responseBuilder;
    }

    /**
     * Stream agent events as SSE text deltas.
     *
     * <p>Each SSE "data" field contains a text delta from a REASONING event. Clients can
     * accumulate these deltas to reconstruct the full assistant message, similar to OpenAI
     * streaming.
     *
     * @param agent The agent to stream from
     * @param messages The messages to send to the agent
     * @param requestId The request ID for tracking
     * @return Flux of Server-Sent Events
     */
    public Flux<ServerSentEvent<String>> streamAsSse(
            ReActAgent agent, List<Msg> messages, String requestId) {
        StreamOptions options =
                StreamOptions.builder().eventTypes(EventType.REASONING).incremental(true).build();

        return agent.stream(messages, options)
                .filter(event -> event.getMessage() != null)
                .flatMap(
                        event -> {
                            ServerSentEvent<String> sse = eventToSse(event, requestId);
                            if (sse == null || sse.data() == null || sse.data().isEmpty()) {
                                return Flux.empty();
                            }
                            return Flux.just(sse);
                        })
                .concatWith(Flux.just(createDoneSseEvent(requestId)));
    }

    /**
     * Convert an agent event to a Server-Sent Event.
     *
     * @param event The agent event
     * @param requestId The request ID for tracking
     * @return The SSE event, or null if no text content
     */
    private ServerSentEvent<String> eventToSse(Event event, String requestId) {
        String text = responseBuilder.extractTextContent(event.getMessage());

        if (text == null || text.isEmpty()) {
            return null;
        }

        ServerSentEvent.Builder<String> builder =
                ServerSentEvent.<String>builder()
                        .data(text)
                        .event("delta")
                        .id(event.getMessageId() != null ? event.getMessageId() : requestId);

        if (event.isLast()) {
            builder.comment("end");
        }

        return builder.build();
    }

    /**
     * Create a done event to signal stream completion.
     *
     * @param requestId The request ID
     * @return The done SSE event
     */
    private ServerSentEvent<String> createDoneSseEvent(String requestId) {
        return ServerSentEvent.<String>builder().data("[DONE]").event("done").id(requestId).build();
    }

    /**
     * Create an error SSE event.
     *
     * @param error The error that occurred
     * @param requestId The request ID
     * @return The error SSE event
     */
    public ServerSentEvent<String> createErrorSseEvent(Throwable error, String requestId) {
        String errorMessage = error != null ? error.getMessage() : "Unknown error occurred";
        return ServerSentEvent.<String>builder()
                .data("Error: " + errorMessage)
                .event("error")
                .id(requestId)
                .build();
    }
}
