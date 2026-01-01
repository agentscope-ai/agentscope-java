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
package io.agentscope.examples.werewolf.web;

import io.agentscope.examples.werewolf.entity.Role;

/**
 * Defines the visibility of game events based on player roles.
 *
 * <p>This enum controls which events are visible to the human player based on their assigned role.
 * For example, werewolf discussions are only visible to werewolf players.
 */
public enum EventVisibility {

    /** Event is visible to all players. */
    PUBLIC,

    /** Event is only visible to werewolf players. */
    WEREWOLF_ONLY,

    /** Event is only visible to the seer. */
    SEER_ONLY,

    /** Event is only visible to the witch. */
    WITCH_ONLY,

    /** Event is only visible to the hunter. */
    HUNTER_ONLY,

    /** Event is only visible in god view (replay after game ends). */
    GOD_VIEW_ONLY;

    /**
     * Check if this event visibility allows the given player role to see the event.
     *
     * @param playerRole The role of the human player
     * @return true if the player can see events with this visibility
     */
    public boolean isVisibleTo(Role playerRole) {
        if (playerRole == null) {
            return this == PUBLIC;
        }
        return switch (this) {
            case PUBLIC -> true;
            case WEREWOLF_ONLY -> playerRole == Role.WEREWOLF;
            case SEER_ONLY -> playerRole == Role.SEER;
            case WITCH_ONLY -> playerRole == Role.WITCH;
            case HUNTER_ONLY -> playerRole == Role.HUNTER;
            case GOD_VIEW_ONLY -> false;
        };
    }
}
