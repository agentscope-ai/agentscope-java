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
package io.agentscope.core.model.tts;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Predefined voices for Qwen3 TTS Flash / Realtime models.
 *
 * <p>The {@code voiceId} values correspond to the {@code voice} parameter
 * accepted by qwen3-tts-flash and qwen3-tts-flash-realtime.
 */
public enum Qwen3TTSFlashVoice {

    /**
     * 芊悦 (Cherry) - A sunny, positive, friendly, and natural young woman.
     */
    CHERRY("Cherry", "芊悦", Gender.FEMALE, "A sunny, positive, friendly, and natural young woman"),

    /**
     * 晨煦 (Ethan) - A bright, warm, energetic, and vibrant male voice with a standard Mandarin pronunciation and a slight northern accent.
     */
    ETHAN(
            "Ethan",
            "晨煦",
            Gender.MALE,
            "A bright, warm, energetic, and vibrant male voice with a standard Mandarin"
                    + " pronunciation and a slight northern accent"),

    /**
     * 不吃鱼 (Nofish) - A male designer who cannot pronounce retroflex sounds.
     */
    NOFISH("Nofish", "不吃鱼", Gender.MALE, "A male designer who cannot pronounce retroflex sounds"),

    /**
     * 詹妮弗 (Jennifer) - A premium, cinematic American English female voice.
     */
    JENNIFER(
            "Jennifer", "詹妮弗", Gender.FEMALE, "A premium, cinematic American English female voice"),

    /**
     * 甜茶 (Ryan) - A rhythmic and dramatic voice with a sense of realism and tension.
     */
    RYAN(
            "Ryan",
            "甜茶",
            Gender.MALE,
            "A rhythmic and dramatic voice with a sense of realism and tension"),

    /**
     * 卡捷琳娜 (Katerina) - A mature female voice with a rich rhythm and lingering resonance.
     */
    KATERINA(
            "Katerina",
            "卡捷琳娜",
            Gender.FEMALE,
            "A mature female voice with a rich rhythm and lingering resonance"),

    /**
     * 墨讲师 (Elias) - A voice that maintains academic rigor while using storytelling techniques to transform complex knowledge into digestible cognitive modules.
     */
    ELIAS(
            "Elias",
            "墨讲师",
            Gender.MALE,
            "A voice that maintains academic rigor while using storytelling techniques to transform"
                    + " complex knowledge into digestible cognitive modules"),

    /**
     * 上海-阿珍 (Jada) - An energetic woman from Shanghai.
     */
    JADA("Jada", "上海-阿珍", Gender.FEMALE, "An energetic woman from Shanghai"),

    /**
     * 北京-晓东 (Dylan) - A teenage boy who grew up in the hutongs of Beijing.
     */
    DYLAN("Dylan", "北京-晓东", Gender.MALE, "A teenage boy who grew up in the hutongs of Beijing"),

    /**
     * 四川-晴儿 (Sunny) - The voice of a Sichuan girl whose sweetness melts your heart.
     */
    SUNNY(
            "Sunny",
            "四川-晴儿",
            Gender.FEMALE,
            "The voice of a Sichuan girl whose sweetness melts your heart"),

    /**
     * 南京-老李 (li) - Patient male yoga instructor.
     */
    LI("li", "南京-老李", Gender.MALE, "Patient male yoga instructor"),

    /**
     * 陕西-秦川 (Marcus) - A voice that is broad-faced and brief-spoken, sincere-hearted and deep-voiced—the authentic flavor of Shaanxi.
     */
    MARCUS(
            "Marcus",
            "陕西-秦川",
            Gender.MALE,
            "A voice that is broad-faced and brief-spoken, sincere-hearted and deep-voiced—the"
                    + " authentic flavor of Shaanxi"),

    /**
     * 闽南-阿杰 (Roy) - The voice of a humorous, straightforward, and lively young Taiwanese man.
     */
    ROY(
            "Roy",
            "闽南-阿杰",
            Gender.MALE,
            "The voice of a humorous, straightforward, and lively young Taiwanese man"),

    /**
     * 天津-李彼得 (Peter) - The voice of a professional straight man in Tianjin crosstalk.
     */
    PETER(
            "Peter",
            "天津-李彼得",
            Gender.MALE,
            "The voice of a professional straight man in Tianjin crosstalk"),

    /**
     * 粤语-阿强 (Rocky) - The voice of the humorous and witty Rocky, here for online chatting.
     */
    ROCKY(
            "Rocky",
            "粤语-阿强",
            Gender.MALE,
            "The voice of the humorous and witty Rocky, here for online chatting"),

    /**
     * 粤语-阿清 (Kiki) - A sweet female companion from Hong Kong.
     */
    KIKI("Kiki", "粤语-阿清", Gender.FEMALE, "A sweet female companion from Hong Kong"),

    /**
     * 四川-程川 (Eric) - An unconventional man from Chengdu, Sichuan.
     */
    ERIC("Eric", "四川-程川", Gender.MALE, "An unconventional man from Chengdu, Sichuan");

    private final String voiceId;
    private final String displayName;
    private final Gender gender;
    private final String description;

    Qwen3TTSFlashVoice(String voiceId, String displayName, Gender gender, String description) {
        this.voiceId = voiceId;
        this.displayName = displayName;
        this.gender = gender;
        this.description = description;
    }

    /**
     * Voice id to use as the {@code voice} parameter in DashScope TTS requests.
     */
    public String getVoiceId() {
        return voiceId;
    }

    /**
     * Human friendly display name (typically Chinese).
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gender of this voice (for informational / filtering purposes).
     */
    public Gender getGender() {
        return gender;
    }

    /**
     * Short description of the voice characteristics.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Find a voice enum by its voiceId (case-insensitive).
     *
     * @param voiceId the voice id string, e.g. "Cherry"
     * @return matching enum value, or {@code null} if not found
     */
    public static Qwen3TTSFlashVoice fromVoiceId(String voiceId) {
        if (voiceId == null || voiceId.isEmpty()) {
            return null;
        }
        String normalized = voiceId.toLowerCase(Locale.ROOT);
        for (Qwen3TTSFlashVoice v : values()) {
            if (v.voiceId.toLowerCase(Locale.ROOT).equals(normalized)) {
                return v;
            }
        }
        return null;
    }

    /**
     * Pick a random voice using {@link ThreadLocalRandom}.
     */
    public static Qwen3TTSFlashVoice random() {
        return random(ThreadLocalRandom.current());
    }

    /**
     * Pick a random voice using the provided {@link Random} instance.
     */
    public static Qwen3TTSFlashVoice random(Random random) {
        Qwen3TTSFlashVoice[] all = values();
        if (all.length == 0) {
            throw new IllegalStateException("No Qwen3TTSFlashVoice defined");
        }
        int idx = random.nextInt(all.length);
        return all[idx];
    }

    /** Simple gender enum for voices. */
    public enum Gender {
        MALE,
        FEMALE
    }
}
