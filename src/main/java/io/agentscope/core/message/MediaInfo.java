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

/**
 * Information about media content in a ContentBlock.
 */
public class MediaInfo {

    public enum SourceType {
        URL,
        BASE64
    }

    private final String mimeType;
    private final String data;
    private final SourceType sourceType;

    public MediaInfo(String mimeType, String data, SourceType sourceType) {
        this.mimeType = mimeType;
        this.data = data;
        this.sourceType = sourceType;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getData() {
        return data;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    @Override
    public String toString() {
        return "MediaInfo{"
                + "mimeType='"
                + mimeType
                + '\''
                + ", sourceType="
                + sourceType
                + ", data='"
                + (data.length() > 50 ? data.substring(0, 50) + "..." : data)
                + '\''
                + '}';
    }
}
