package io.agentscope.core.tracing.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Represents an external referenced file sent to the model by URI. */
@JsonClassDescription("Uri part")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UriPart implements MessagePart {

    private final String type;

    private final String uri;

    private final String mimeType;

    private final String modality;

    @JsonProperty(required = true, value = "type")
    @JsonPropertyDescription("The type of the content captured in this part")
    @Override
    public String getType() {
        return this.type;
    }

    @JsonProperty(required = true, value = "uri")
    @JsonPropertyDescription(
            "A URI referencing attached data. It should not be a base64 data URL, which should use"
                    + " the `blob` part instead. The URI may use a scheme known to the provider api"
                    + " (e.g. `gs://bucket/object.png`), or be a publicly accessible location")
    public String getUri() {
        return this.uri;
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

    public static UriPart create(String uri, String mimeType, String modality) {
        return new UriPart("uri", uri, mimeType, modality);
    }

    private UriPart(String type, String uri, String mimeType, String modality) {
        this.type = type;
        this.uri = uri;
        this.mimeType = mimeType;
        this.modality = modality;
    }
}
