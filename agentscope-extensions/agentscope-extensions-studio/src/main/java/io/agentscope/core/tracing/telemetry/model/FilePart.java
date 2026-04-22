/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.tracing.telemetry.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Represents an external referenced file sent to the model by file id. */
@JsonClassDescription("File part")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FilePart implements MessagePart {

    private final String type;

    private final String fileId;

    private final String mimeType;

    private final String modality;

    @JsonProperty(required = true, value = "type")
    @JsonPropertyDescription("The type of the content captured in this part")
    @Override
    public String getType() {
        return this.type;
    }

    @JsonProperty(required = true, value = "file_id")
    @JsonPropertyDescription(
            "An identifier referencing a file that was pre-uploaded to the provider")
    public String getFileId() {
        return this.fileId;
    }

    @JsonProperty(value = "mime_type")
    @JsonPropertyDescription("The IANA MIME type of the attached data")
    public String getMimeType() {
        return mimeType;
    }

    @JsonProperty(required = true, value = "modality")
    @JsonPropertyDescription(
            "The general modality of the data if it is known. Instrumentations SHOULD also set the"
                    + " mimeType field if the specific type is known")
    public String getModality() {
        return modality;
    }

    public static FilePart create(String fileId, String mimeType, String modality) {
        return new FilePart("file", fileId, mimeType, modality);
    }

    private FilePart(String type, String fileId, String mimeType, String modality) {
        this.type = type;
        this.fileId = fileId;
        this.mimeType = mimeType;
        this.modality = modality;
    }
}
