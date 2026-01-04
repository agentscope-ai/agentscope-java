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
package io.agentscope.examples.werewolf.localization;

import java.util.List;

/**
 * Interface for language-specific configuration.
 *
 * <p>This interface defines language-dependent configuration such as player names.
 */
public interface LanguageConfig {

    /**
     * Get the list of player names for this language.
     *
     * <p>Must contain at least 9 names for the 9 players in the game.
     *
     * @return List of player names
     */
    List<String> getPlayerNames();
}
