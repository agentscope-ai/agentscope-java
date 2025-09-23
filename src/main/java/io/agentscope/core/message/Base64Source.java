/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.message;

public class Base64Source extends Source {

    private final String mediaType;

    private final String data;

    public Base64Source(String mediaType, String data) {
        this.mediaType = mediaType;
        this.data = data;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getData() {
        return data;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String mediaType;

        private String data;

        public Builder mediaType(String mediaType) {
            this.mediaType = mediaType;
            return this;
        }

        public Builder data(String data) {
            this.data = data;
            return this;
        }

        public Base64Source build() {
            return new Base64Source(mediaType, data);
        }
    }
}
