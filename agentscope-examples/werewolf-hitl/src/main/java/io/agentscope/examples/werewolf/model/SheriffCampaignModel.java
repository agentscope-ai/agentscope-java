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
package io.agentscope.examples.werewolf.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Model for sheriff campaign speech.
 */
public class SheriffCampaignModel {
    @JsonPropertyDescription("昨夜查验的结果，比如xx号金水，xx号查杀。注意：如果不是预言家，或者不准备跳预言家，则严格留空")
    public String checkResult; // Seer's check result to announce (if seer)

    @JsonPropertyDescription("今晚查验的目标。注意：如果不是预言家，或者不准备跳预言家，则严格留空")
    public String nextCheckTarget; // Next player to check tonight (if seer)

    @JsonPropertyDescription("警上发言内容，不能为空")
    public String campaignSpeech; // Campaign speech content
}
