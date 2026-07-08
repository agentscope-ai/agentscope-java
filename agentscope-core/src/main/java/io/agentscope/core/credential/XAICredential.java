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
package io.agentscope.core.credential;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.agentscope.core.model.ChatModelBase;
import java.util.Objects;

/**
 * Credential for the xAI API.
 *
 * <p>No {@code XAIChatModel} class ships in agentscope-core yet, so {@link #getChatModelClass()}
 * throws {@link UnsupportedOperationException}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "type", "api_key"})
public final class XAICredential extends CredentialBase {

    public static final String TYPE = "xai_credential";

    private final String apiKey;

    private XAICredential(String id, String apiKey) {
        super(id);
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
    }

    @JsonCreator
    static XAICredential fromJson(
            @JsonProperty("id") String id, @JsonProperty("api_key") String apiKey) {
        return new XAICredential(id, apiKey);
    }

    @JsonProperty("type")
    public String getType() {
        return TYPE;
    }

    @JsonProperty("api_key")
    public String getApiKey() {
        return apiKey;
    }

    @Override
    public Class<? extends ChatModelBase> getChatModelClass() {
        throw new UnsupportedOperationException(
                "XAIChatModel is not implemented in agentscope-core yet."
                        + " Use io.agentscope.extensions.model.openai.OpenAIChatModel against the"
                        + " xAI base URL instead.");
    }

    @Override
    public String toString() {
        return "XAICredential{id=" + getId() + ", apiKey=***}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String apiKey;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public XAICredential build() {
            return new XAICredential(id, apiKey);
        }
    }
}
