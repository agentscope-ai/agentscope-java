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

/**
 * Event types for the Werewolf game web interface.
 */
public enum GameEventType {
    /** Game initialization with player info. */
    GAME_INIT,

    /** Phase change (night/day). */
    PHASE_CHANGE,

    /** Player speaks during discussion. */
    PLAYER_SPEAK,

    /** Player casts a vote. */
    PLAYER_VOTE,

    /** Special role action (witch/seer/hunter). */
    PLAYER_ACTION,

    /** Player is eliminated. */
    PLAYER_ELIMINATED,

    /** Player is resurrected (by witch). */
    PLAYER_RESURRECTED,

    /** Game statistics update. */
    STATS_UPDATE,

    /** System message/announcement. */
    SYSTEM_MESSAGE,

    /** Game ends with winner. */
    GAME_END,

    /** Error occurred. */
    ERROR,

    /** Human player's role assignment (tells the player their role). */
    PLAYER_ROLE_ASSIGNMENT,

    /** Waiting for user input (prompts the human player to act). */
    WAIT_USER_INPUT,

    /** User input received confirmation. */
    USER_INPUT_RECEIVED
}
