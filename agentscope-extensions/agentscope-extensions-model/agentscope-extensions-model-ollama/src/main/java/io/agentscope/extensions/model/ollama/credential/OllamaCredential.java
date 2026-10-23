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
package io.agentscope.extensions.model.ollama.credential;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.agentscope.core.credential.CredentialBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.extensions.model.ollama.OllamaChatModel;

/**
 * Credential for the Ollama local server (connection settings only).
 *
 * <p>{@code host} may be {@code null}, in which case the model class falls back to {@code
 * http://localhost:11434}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "type", "host"})
public final class OllamaCredential extends CredentialBase {

    public static final String TYPE = "ollama_credential";

    private final String host;

    private OllamaCredential(String id, String host) {
        super(id);
        this.host = host;
    }

    @JsonCreator
    static OllamaCredential fromJson(
            @JsonProperty("id") String id, @JsonProperty("host") String host) {
        return new OllamaCredential(id, host);
    }

    @JsonProperty("type")
    public String getType() {
        return TYPE;
    }

    @JsonProperty("host")
    public String getHost() {
        return host;
    }

    @Override
    public Class<? extends ChatModelBase> getChatModelClass() {
        return OllamaChatModel.class;
    }

    @Override
    public String toString() {
        return "OllamaCredential{id=" + getId() + ", host=" + host + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String host;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public OllamaCredential build() {
            return new OllamaCredential(id, host);
        }
    }
}
