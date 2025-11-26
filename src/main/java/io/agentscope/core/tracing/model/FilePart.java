package io.agentscope.core.tracing.model;

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
  @JsonPropertyDescription("An identifier referencing a file that was pre-uploaded to the provider")
  public String getFileId() {
    return this.fileId;
  }

  @JsonProperty(value = "mime_type")
  @JsonPropertyDescription("The IANA MIME type of the attached data")
  public String getMimeType() {
    return mimeType;
  }

  @JsonProperty(required = true, value = "modality")
  @JsonPropertyDescription("The general modality of the data if it is known. Instrumentations SHOULD also set the mimeType field if the specific type is known")
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
