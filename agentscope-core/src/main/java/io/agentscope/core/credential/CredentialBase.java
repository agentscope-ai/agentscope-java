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

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.model.ChatModelBase;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Credential metadata for a model provider.
 *
 * <p>Each credential carries the connection material (API key, base URL, host, etc.) needed by a
 * specific {@link ChatModelBase} subclass. Subclasses declare which model class consumes them via
 * {@link #getChatModelClass()} and optionally enumerate the catalog of usable models via {@link
 * #listModels()}.
 *
 * <p>{@code id} is an auto-generated 32-char hex string identifying this credential instance; it is
 * carried in JSON for round-tripping but not used for authentication itself.
 */
public abstract class CredentialBase {

    private final String id;

    protected CredentialBase(String id) {
        this.id = id == null ? UUID.randomUUID().toString().replace("-", "") : id;
    }

    @JsonProperty("id")
    public final String getId() {
        return id;
    }

    /**
     * The {@link ChatModelBase} subclass that consumes this credential. Subclasses without a
     * matching Java model implementation throw {@link UnsupportedOperationException}.
     */
    public abstract Class<? extends ChatModelBase> getChatModelClass();

    /**
     * Enumerate the candidate models available under this credential. The default implementation
     * throws {@link UnsupportedOperationException}; concrete model discovery is provided by a
     * future {@code ChatModelBase.listModels} hook.
     */
    public Mono<List<ModelCard>> listModels() {
        return Mono.error(
                new UnsupportedOperationException(
                        getClass().getSimpleName()
                                + " does not yet support listModels; provide a manual model"
                                + " catalog or wait for ChatModelBase.listModels support."));
    }
}
