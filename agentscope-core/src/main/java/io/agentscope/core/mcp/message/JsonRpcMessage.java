package io.agentscope.core.mcp.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Optional;

/**
 * Base class for JSON-RPC 2.0 messages.
 *
 * <p>Represents both requests and responses as per JSON-RPC 2.0 specification.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = JsonRpcRequest.class)
@JsonSubTypes({
    @JsonSubTypes.Type(JsonRpcRequest.class),
    @JsonSubTypes.Type(JsonRpcResponse.class),
    @JsonSubTypes.Type(JsonRpcNotification.class)
})
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class JsonRpcMessage {

    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";

    protected JsonRpcMessage() {}

    protected JsonRpcMessage(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    /**
     * Get the message ID if this is a request or response.
     *
     * @return optional ID
     */
    public abstract Optional<Object> getId();

    /**
     * Get the method name if this is a request or notification.
     *
     * @return optional method name
     */
    public abstract Optional<String> getMethod();
}
