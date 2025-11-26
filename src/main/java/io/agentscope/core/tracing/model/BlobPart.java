package io.agentscope.core.tracing.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Represents blob binary data sent inline to the model. */
@JsonClassDescription("Blob part")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BlobPart implements MessagePart {

    private final String type;

    private final String content;

    private final String mimeType;

    private final String modality;

    @JsonProperty(required = true, value = "type")
    @JsonPropertyDescription("The type of the content captured in this part")
    @Override
    public String getType() {
        return this.type;
    }

    @JsonProperty(required = true, value = "content")
    @JsonPropertyDescription(
            "Raw bytes of the attached data. This field SHOULD be encoded as a base64 string when"
                    + " serialized to JSON")
    public String getContent() {
        return this.content;
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

    public static BlobPart create(String content, String mimeType, String modality) {
        return new BlobPart("blob", content, mimeType, modality);
    }

    private BlobPart(String type, String content, String mimeType, String modality) {
        this.type = type;
        this.content = content;
        this.mimeType = mimeType;
        this.modality = modality;
    }
}
