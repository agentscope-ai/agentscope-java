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
package io.agentscope.extensions.model.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope {@code skill} parameter for document-capable models such as {@code qwen-doc-turbo}.
 *
 * <p>Currently only PowerPoint generation is supported. The skill requires streaming: a
 * non-streaming request is rejected by the API with
 * {@code The parameter skill is only supported in stream mode}, so build the model with
 * {@code stream(true)}.
 *
 * <p>The outline and page content stream back through {@code reasoning_content} while the final
 * PPT download URL arrives in {@code content}.
 *
 * <p>Example request parameters:
 * <pre>{@code
 * "parameters": {
 *   "skill": [{"type": "ppt", "mode": "general", "template_id": "news_01"}]
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeSkill {

    /** Skill type for PowerPoint generation. */
    public static final String TYPE_PPT = "ppt";

    /**
     * Metadata key set on the assistant reply when the response was produced with a skill.
     *
     * <p>In skill mode the outline and page content stream through {@code reasoning_content}
     * (thinking blocks) while the final download URL arrives in {@code content} (text blocks).
     * The value is the skill type, e.g. {@link #TYPE_PPT}, so consumers can identify skill output.
     * The download URL remains in the text content; this metadata does not contain the URL.
     *
     * <p><b>Type:</b> String (skill type)
     */
    public static final String RESPONSE_METADATA_KEY = "dashscope.skill";

    /** Template mode: the PPT follows one of the preset templates. */
    public static final String MODE_GENERAL = "general";

    /** Creative mode: the model designs the layout itself and renders pages as images. */
    public static final String MODE_CREATIVE = "creative";

    /** Built-in template id for news-style presentations. */
    public static final String TEMPLATE_NEWS = "news_01";

    /** Built-in template id for summary presentations. */
    public static final String TEMPLATE_SUMMARY = "summary_01";

    /** Built-in template id for internet-style presentations. */
    public static final String TEMPLATE_INTERNET = "internet_01";

    /** Built-in template id for thesis presentations. */
    public static final String TEMPLATE_THESIS = "thesis_01";

    /** Skill type; currently only {@link #TYPE_PPT}. */
    @JsonProperty("type")
    private String type;

    /** {@link #MODE_GENERAL} (default) or {@link #MODE_CREATIVE}. */
    @JsonProperty("mode")
    private String mode;

    /**
     * Preset template id, only meaningful in {@link #MODE_GENERAL}. One of {@link #TEMPLATE_NEWS},
     * {@link #TEMPLATE_SUMMARY}, {@link #TEMPLATE_INTERNET} or {@link #TEMPLATE_THESIS}.
     */
    @JsonProperty("template_id")
    private String templateId;

    public DashScopeSkill() {}

    /**
     * Get the skill type.
     *
     * @return the skill type (e.g. "ppt")
     */
    public String getType() {
        return type;
    }

    /**
     * Set the skill type.
     *
     * @param type the skill type (e.g. "ppt")
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * Get the generation mode.
     *
     * @return "general" or "creative", or null for the API default ("general")
     */
    public String getMode() {
        return mode;
    }

    /**
     * Set the generation mode.
     *
     * @param mode "general" or "creative"
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * Get the preset template id.
     *
     * @return the template id, or null when unset or when using creative mode
     */
    public String getTemplateId() {
        return templateId;
    }

    /**
     * Set the preset template id. Only meaningful in template mode.
     *
     * @param templateId one of the built-in template ids (e.g. "news_01")
     */
    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    /**
     * Create a PPT skill in template mode with the default template.
     *
     * @return a PPT skill using the API default template
     */
    public static DashScopeSkill ppt() {
        return builder().type(TYPE_PPT).mode(MODE_GENERAL).build();
    }

    /**
     * Create a PPT skill in template mode with a preset template.
     *
     * @param templateId one of the built-in template ids (e.g. {@link #TEMPLATE_NEWS})
     * @return a PPT skill bound to the given template
     */
    public static DashScopeSkill pptTemplate(String templateId) {
        return builder().type(TYPE_PPT).mode(MODE_GENERAL).templateId(templateId).build();
    }

    /**
     * Create a PPT skill in creative mode. The model designs the layout itself and renders each
     * page as an image; only a single document is supported per request.
     *
     * @return a PPT skill in creative mode
     */
    public static DashScopeSkill pptCreative() {
        return builder().type(TYPE_PPT).mode(MODE_CREATIVE).build();
    }

    /**
     * Create a builder for DashScopeSkill.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for DashScopeSkill. */
    public static class Builder {

        private String type;
        private String mode;
        private String templateId;

        /**
         * Set the skill type.
         *
         * @param type the skill type; only {@link #TYPE_PPT} is supported
         * @return this builder
         */
        public Builder type(String type) {
            this.type = type;
            return this;
        }

        /**
         * Set the generation mode.
         *
         * @param mode {@link #MODE_GENERAL} or {@link #MODE_CREATIVE}
         * @return this builder
         */
        public Builder mode(String mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Set the preset template id (template mode only).
         *
         * @param templateId one of the built-in template ids
         * @return this builder
         */
        public Builder templateId(String templateId) {
            this.templateId = templateId;
            return this;
        }

        /**
         * Build the skill.
         *
         * @return a new DashScopeSkill instance
         * @throws IllegalArgumentException if the type is not "ppt", the mode is unknown, or a
         *     template id is combined with creative mode
         */
        public DashScopeSkill build() {
            if (!TYPE_PPT.equals(type)) {
                throw new IllegalArgumentException(
                        "DashScope skill type must be \"ppt\", got "
                                + (type == null ? "null" : type));
            }
            if (mode != null && !MODE_GENERAL.equals(mode) && !MODE_CREATIVE.equals(mode)) {
                throw new IllegalArgumentException(
                        "DashScope PPT skill mode must be \"general\" or \"creative\", got "
                                + mode);
            }
            if (templateId != null && MODE_CREATIVE.equals(mode)) {
                throw new IllegalArgumentException(
                        "DashScope PPT skill does not accept a template_id in creative mode");
            }
            DashScopeSkill skill = new DashScopeSkill();
            skill.setType(type);
            skill.setMode(mode);
            skill.setTemplateId(templateId);
            return skill;
        }
    }
}
