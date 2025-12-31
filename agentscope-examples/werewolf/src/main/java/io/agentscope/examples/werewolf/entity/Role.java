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
package io.agentscope.examples.werewolf.entity;

/**
 * Represents different roles in the Werewolf game.
 */
public enum Role {
    VILLAGER("Villager", "villager"),
    WEREWOLF("Werewolf", "werewolf"),
    SEER("Seer", "seer"),
    WITCH("Witch", "witch"),
    HUNTER("Hunter", "hunter");

    private final String displayName;
    private final String camp; // "villager" or "werewolf"

    Role(String displayName, String camp) {
        this.displayName = displayName;
        this.camp = camp;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCamp() {
        return camp;
    }

    public boolean isWerewolf() {
        return this == WEREWOLF;
    }

    public boolean isVillagerCamp() {
        return !isWerewolf();
    }
}
