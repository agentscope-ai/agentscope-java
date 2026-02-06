/*
 * Qwen3 TTS Flash / Realtime voices enumeration.
 *
 * This enum lists the officially documented 17 timbres for
 * qwen3-tts-flash / qwen3-tts-flash-realtime models.
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
     * 芊悦 (Cherry) - 阳光积极、亲切自然小姐姐。
     */
    CHERRY("Cherry", "芊悦", Gender.FEMALE, "阳光积极、亲切自然小姐姐"),

    /**
     * 晨煦 (Ethan) - 标准普通话，带部分北方口音。阳光、温暖、活力、朝气。
     */
    ETHAN("Ethan", "晨煦", Gender.MALE, "标准普通话，带部分北方口音，阳光温暖、活力十足"),

    /**
     * 不吃鱼 (Nofish) - 不会翘舌音的设计师。
     */
    NOFISH("Nofish", "不吃鱼", Gender.MALE, "不会翘舌音的设计师"),

    /**
     * 詹妮弗 (Jennifer) - 品牌级、电影质感般美语女声。
     */
    JENNIFER("Jennifer", "詹妮弗", Gender.FEMALE, "品牌级、电影质感般美语女声"),

    /**
     * 甜茶 (Ryan) - 节奏拉满，戏感炸裂，真实与张力共舞。
     */
    RYAN("Ryan", "甜茶", Gender.MALE, "节奏拉满、戏感炸裂的男声"),

    /**
     * 卡捷琳娜 (Katerina) - 御姐音色，韵律回味十足。
     */
    KATERINA("Katerina", "卡捷琳娜", Gender.FEMALE, "御姐音色，韵律回味十足"),

    /**
     * 墨讲师 (Elias) - 兼具严谨与叙事性的讲师音色。
     */
    ELIAS("Elias", "墨讲师", Gender.FEMALE, "兼具严谨与叙事性的讲师音色"),

    /**
     * 上海-阿珍 (Jada) - 风风火火的沪上阿姐。
     */
    JADA("Jada", "上海-阿珍", Gender.FEMALE, "风风火火的沪上阿姐"),

    /**
     * 北京-晓东 (Dylan) - 北京胡同里长大的少年。
     */
    DYLAN("Dylan", "北京-晓东", Gender.MALE, "北京胡同里长大的少年"),

    /**
     * 四川-晴儿 (Sunny) - 甜到你心里的川妹子。
     */
    SUNNY("Sunny", "四川-晴儿", Gender.FEMALE, "甜到你心里的川妹子"),

    /**
     * 南京-老李 (li) - 耐心的瑜伽老师。
     */
    LI("li", "南京-老李", Gender.MALE, "耐心的瑜伽老师"),

    /**
     * 陕西-秦川 (Marcus) - 面宽话短，心实声沉的老陕味道。
     */
    MARCUS("Marcus", "陕西-秦川", Gender.MALE, "面宽话短、心实声沉的老陕味道"),

    /**
     * 闽南-阿杰 (Roy) - 诙谐直爽、市井活泼的中国台湾哥仔。
     */
    ROY("Roy", "闽南-阿杰", Gender.MALE, "诙谐直爽、市井活泼的台湾哥仔"),

    /**
     * 天津-李彼得 (Peter) - 天津相声，专业捧人。
     */
    PETER("Peter", "天津-李彼得", Gender.MALE, "天津相声风格的专业捧人"),

    /**
     * 粤语-阿强 (Rocky) - 幽默风趣的阿强，在线陪聊。
     */
    ROCKY("Rocky", "粤语-阿强", Gender.MALE, "幽默风趣的粤语阿强"),

    /**
     * 粤语-阿清 (Kiki) - 甜美的港妹闺蜜。
     */
    KIKI("Kiki", "粤语-阿清", Gender.FEMALE, "甜美的港妹闺蜜"),

    /**
     * 四川-程川 (Eric) - 一个跳脱市井的四川成都男子。
     */
    ERIC("Eric", "四川-程川", Gender.MALE, "跳脱市井的四川成都男子");

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
