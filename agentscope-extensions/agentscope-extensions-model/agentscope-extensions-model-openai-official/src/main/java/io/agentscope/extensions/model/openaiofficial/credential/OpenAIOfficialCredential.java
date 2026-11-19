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
package io.agentscope.extensions.model.openaiofficial.credential;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.agentscope.core.credential.CredentialBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import java.util.Objects;

/** Credential for the OpenAI official SDK provider. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "type", "api_key", "organization", "base_url"})
public final class OpenAIOfficialCredential extends CredentialBase {

    public static final String TYPE = "openai_official_credential";

    private final String apiKey;
    private final String organization;
    private final String baseUrl;

    private OpenAIOfficialCredential(
            String id, String apiKey, String organization, String baseUrl) {
        super(id);
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.organization = organization;
        this.baseUrl = baseUrl;
    }

    @JsonCreator
    static OpenAIOfficialCredential fromJson(
            @JsonProperty("id") String id,
            @JsonProperty("api_key") String apiKey,
            @JsonProperty("organization") String organization,
            @JsonProperty("base_url") String baseUrl) {
        return new OpenAIOfficialCredential(id, apiKey, organization, baseUrl);
    }

    @JsonProperty("type")
    public String getType() {
        return TYPE;
    }

    @JsonProperty("api_key")
    public String getApiKey() {
        return apiKey;
    }

    @JsonProperty("organization")
    public String getOrganization() {
        return organization;
    }

    @JsonProperty("base_url")
    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public Class<? extends ChatModelBase> getChatModelClass() {
        return OpenAIResponsesChatModel.class;
    }

    @Override
    public String toString() {
        return "OpenAIOfficialCredential{id="
                + getId()
                + ", organization="
                + organization
                + ", baseUrl="
                + baseUrl
                + ", apiKey=***}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String apiKey;
        private String organization;
        private String baseUrl;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder organization(String organization) {
            this.organization = organization;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public OpenAIOfficialCredential build() {
            return new OpenAIOfficialCredential(id, apiKey, organization, baseUrl);
        }
    }
}
