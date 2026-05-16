package io.agentscope.core.mcp.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

/**
 * JSON-RPC 2.0 Notification message.
 *
 * <p>A notification does not require a response (no id field).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonRpcNotification extends JsonRpcMessage {

    @JsonProperty("method")
    private String method;

    @JsonProperty("params")
    private Object params;

    public JsonRpcNotification() {
        super();
    }

    public JsonRpcNotification(String method, Object params) {
        super();
        this.method = method;
        this.params = params;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Object getParams() {
        return params;
    }

    public void setParams(Object params) {
        this.params = params;
    }

    @Override
    public Optional<Object> getId() {
        return Optional.empty(); // Notifications don't have IDs
    }

    @Override
    public Optional<String> getMethod() {
        return Optional.ofNullable(method);
    }
}
