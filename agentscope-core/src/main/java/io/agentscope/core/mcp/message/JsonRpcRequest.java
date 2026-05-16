package io.agentscope.core.mcp.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

/**
 * JSON-RPC 2.0 Request message.
 *
 * <p>Contains method and parameters that the receiver should execute.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonRpcRequest extends JsonRpcMessage {

    @JsonProperty("id")
    private Object id;

    @JsonProperty("method")
    private String method;

    @JsonProperty("params")
    private Object params;

    public JsonRpcRequest() {
        super();
    }

    public JsonRpcRequest(Object id, String method, Object params) {
        super();
        this.id = id;
        this.method = method;
        this.params = params;
    }

    public void setId(Object id) {
        this.id = id;
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
        return Optional.ofNullable(id);
    }

    @Override
    public Optional<String> getMethod() {
        return Optional.ofNullable(method);
    }
}
