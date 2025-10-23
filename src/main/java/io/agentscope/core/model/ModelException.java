/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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

    public ModelException(String message) {
        super(message);
        this.modelName = null;
        this.provider = null;
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
        this.modelName = null;
        this.provider = null;
    }

    public ModelException(String message, String modelName, String provider) {
        super(message);
        this.modelName = modelName;
        this.provider = provider;
    }

    public ModelException(String message, Throwable cause, String modelName, String provider) {
        super(message, cause);
        this.modelName = modelName;
        this.provider = provider;
    }

    public String getModelName() {
        return modelName;
    }

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
