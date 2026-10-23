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
package io.agentscope.core.embedding;

/**
 * Exception thrown when embedding generation fails.
 *
 * <p>This exception is used to wrap errors from embedding model API calls, providing
 * context about the model and provider that failed.
 */
public class EmbeddingException extends RuntimeException {

    private final String modelName;
    private final String provider;

    /**
     * Creates a new EmbeddingException with the given message.
     *
     * @param message the error message
     */
    public EmbeddingException(String message) {
        super(message);
        this.modelName = null;
        this.provider = null;
    }

    /**
     * Creates a new EmbeddingException with the given message and cause.
     *
     * @param message the error message
     * @param cause the cause of this exception
     */
    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
        this.modelName = null;
        this.provider = null;
    }

    /**
     * Creates a new EmbeddingException with the given message, model name, and provider.
     *
     * @param message the error message
     * @param modelName the name of the model that failed
     * @param provider the provider name (e.g., "dashscope", "openai")
     */
    public EmbeddingException(String message, String modelName, String provider) {
        super(message);
        this.modelName = modelName;
        this.provider = provider;
    }

    /**
     * Creates a new EmbeddingException with the given message, cause, model name, and provider.
     *
     * @param message the error message
     * @param cause the cause of this exception
     * @param modelName the name of the model that failed
     * @param provider the provider name (e.g., "dashscope", "openai")
     */
    public EmbeddingException(String message, Throwable cause, String modelName, String provider) {
        super(message, cause);
        this.modelName = modelName;
        this.provider = provider;
    }

    /**
     * Gets the model name that failed.
     *
     * @return the model name, or null if not set
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * Gets the provider name.
     *
     * @return the provider name, or null if not set
     */
    public String getProvider() {
        return provider;
    }
}
