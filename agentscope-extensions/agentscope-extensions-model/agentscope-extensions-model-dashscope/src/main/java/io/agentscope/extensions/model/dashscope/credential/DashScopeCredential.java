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
package io.agentscope.extensions.model.dashscope.credential;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.agentscope.core.credential.CredentialBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import java.util.Objects;

/** Credential for the DashScope (Alibaba Cloud) OpenAI-compatible API. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "type", "api_key", "base_url"})
public final class DashScopeCredential extends CredentialBase {

    public static final String TYPE = "dashscope_credential";

    public static final String DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private final String apiKey;
    private final String baseUrl;

    private DashScopeCredential(String id, String apiKey, String baseUrl) {
        super(id);
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.baseUrl = baseUrl == null ? DEFAULT_BASE_URL : baseUrl;
    }

    @JsonCreator
    static DashScopeCredential fromJson(
            @JsonProperty("id") String id,
            @JsonProperty("api_key") String apiKey,
            @JsonProperty("base_url") String baseUrl) {
        return new DashScopeCredential(id, apiKey, baseUrl);
    }

    @JsonProperty("type")
    public String getType() {
        return TYPE;
    }

    @JsonProperty("api_key")
    public String getApiKey() {
        return apiKey;
    }

    @JsonProperty("base_url")
    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public Class<? extends ChatModelBase> getChatModelClass() {
        return DashScopeChatModel.class;
    }

    @Override
    public String toString() {
        return "DashScopeCredential{id=" + getId() + ", baseUrl=" + baseUrl + ", apiKey=***}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String apiKey;
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

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public DashScopeCredential build() {
            return new DashScopeCredential(id, apiKey, baseUrl);
        }
    }
}
