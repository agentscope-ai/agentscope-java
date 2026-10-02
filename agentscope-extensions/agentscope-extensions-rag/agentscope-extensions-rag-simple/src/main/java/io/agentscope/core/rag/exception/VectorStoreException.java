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
package io.agentscope.core.rag.exception;

/**
 * Exception thrown when vector store operations fail.
 *
 * <p>This exception is used to wrap errors from vector database operations, providing
 * context about the store and operation that failed.
 */
public class VectorStoreException extends Exception {

    /**
     * Creates a new VectorStoreException with the given message.
     *
     * @param message the error message
     */
    public VectorStoreException(String message) {
        super(message);
    }

    /**
     * Creates a new VectorStoreException with the given message and cause.
     *
     * @param message the error message
     * @param cause the cause of this exception
     */
    public VectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
