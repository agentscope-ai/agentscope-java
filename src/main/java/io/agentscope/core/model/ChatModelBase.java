package io.agentscope.core.model;

import static io.agentscope.core.tracing.TelemetryWrappers.traceLLM;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Abstract base class for all models in the AgentScope framework.
 *
 * <p>This class provides common functionality for model including basic model invocation and tracing.
 */
public abstract class ChatModelBase implements Model {

    /**
     * Stream chat completion responses.
     * The model internally handles message formatting using its configured formatter.
     *
     * <p>Tracing data will be captured once telemetry is enabled.
     *
     * @param messages AgentScope messages to send to the model
     * @param tools Optional list of tool schemas (null or empty if no tools)
     * @param options Optional generation options (null to use defaults)
     * @return Flux stream of chat responses
     */
    @Override
    public final Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return traceLLM(this, messages, tools, options, () -> doStream(messages, tools, options));
    }

    /**
     * Internal implementation for streaming chat completion responses.
     * Subclasses must implement their specific logic here.
     *
     * @param messages AgentScope messages to send to the model
     * @param tools Optional list of tool schemas (null or empty if no tools)
     * @param options Optional generation options (null to use defaults)
     * @return Flux stream of chat responses
     */
    protected abstract Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options);
}
