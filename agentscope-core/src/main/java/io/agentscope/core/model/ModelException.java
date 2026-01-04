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
package io.agentscope.core.model;

/**
 * Exception thrown when model operations fail.
 * This exception provides a unified way to handle errors from different model providers.
 */
public class ModelException extends RuntimeException {

    private final String modelName;
    private final String provider;

    /**
     * Creates a ModelException with a message.
     *
     * <p>Use this constructor for general model errors when specific model/provider
     * information is not available or relevant.
     *
     * @param message the error message
     */
    public ModelException(String message) {
        super(message);
        this.modelName = null;
        this.provider = null;
    }

    /**
     * Creates a ModelException with a message and cause.
     *
     * <p>Use this constructor when wrapping another exception that caused the model error.
     *
     * @param message the error message
     * @param cause the underlying cause of the exception
     */
    public ModelException(String message, Throwable cause) {
        super(message, cause);
        this.modelName = null;
        this.provider = null;
    }

    /**
     * Creates a ModelException with message and model information.
     *
     * <p>Use this constructor when the error is specific to a particular model
     * and provider, allowing for more targeted error handling and debugging.
     *
     * @param message the error message
     * @param modelName the name of the model that caused the error
     * @param provider the provider of the model (e.g., "openai", "dashscope")
     */
    public ModelException(String message, String modelName, String provider) {
        super(message);
        this.modelName = modelName;
        this.provider = provider;
    }

    /**
     * Creates a ModelException with message, cause, and model information.
     *
     * <p>Use this constructor when wrapping another exception that caused the model error,
     * and the error is specific to a particular model and provider.
     *
     * @param message the error message
     * @param cause the underlying cause of the exception
     * @param modelName the name of the model that caused the error
     * @param provider the provider of the model (e.g., "openai", "dashscope")
     */
    public ModelException(String message, Throwable cause, String modelName, String provider) {
        super(message, cause);
        this.modelName = modelName;
        this.provider = provider;
    }

    /**
     * Gets the name of the model that caused the error.
     *
     * @return the model name, or null if not set
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * Gets the provider of the model that caused the error.
     *
     * @return the provider name, or null if not set
     */
    public String getProvider() {
        return provider;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        if (modelName != null) {
            sb.append(" [model=").append(modelName);
            if (provider != null) {
                sb.append(", provider=").append(provider);
            }
            sb.append("]");
        }
        return sb.toString();
    }
}
