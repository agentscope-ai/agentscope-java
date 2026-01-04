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
package io.agentscope.examples.werewolf;

/**
 * Configuration constants for the Werewolf game.
 *
 * <p>This class contains language-independent game configuration. Language-specific configurations
 * (such as player names) are handled by {@link
 * io.agentscope.examples.werewolf.localization.LanguageConfig} implementations.
 */
public class WerewolfGameConfig {

    // Role counts (total 9 players)
    public static final int VILLAGER_COUNT = 3;
    public static final int WEREWOLF_COUNT = 3;
    public static final int SEER_COUNT = 1;
    public static final int WITCH_COUNT = 1;
    public static final int HUNTER_COUNT = 1;

    // Game rules
    public static final int MAX_ROUNDS = 30;
    public static final int MAX_DISCUSSION_ROUNDS = 3;

    // Model configuration
    public static final String DEFAULT_MODEL = "qwen3-max";

    private WerewolfGameConfig() {
        // Utility class
    }
}
