package io.agentscope.core.mcp.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

/**
 * JSON-RPC 2.0 Response message.
 *
 * <p>Contains the result of a method call or an error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonRpcResponse extends JsonRpcMessage {

    @JsonProperty("id")
    private Object id;

    @JsonProperty("result")
    private Object result;

    @JsonProperty("error")
    private JsonRpcError error;

    public JsonRpcResponse() {
        super();
    }

    public JsonRpcResponse(Object id, Object result) {
        super();
        this.id = id;
        this.result = result;
    }

    public JsonRpcResponse(Object id, JsonRpcError error) {
        super();
        this.id = id;
        this.error = error;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public JsonRpcError getError() {
        return error;
    }

    public void setError(JsonRpcError error) {
        this.error = error;
    }

    public boolean isError() {
        return error != null;
    }

    @Override
    public Optional<Object> getId() {
        return Optional.ofNullable(id);
    }

    @Override
    public Optional<String> getMethod() {
        return Optional.empty(); // Responses don't have methods
    }
}
