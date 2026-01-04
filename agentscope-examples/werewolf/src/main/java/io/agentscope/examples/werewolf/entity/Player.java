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

import io.agentscope.core.ReActAgent;

/**
 * Represents a player in the Werewolf game.
 */
public class Player {
    private final ReActAgent agent;
    private final String name;
    private final Role role;
    private boolean isAlive;

    // Role-specific state
    private boolean witchHasHealPotion;
    private boolean witchHasPoisonPotion;

    private Player(Builder builder) {
        this.agent = builder.agent;
        this.name = builder.name;
        this.role = builder.role;
        this.isAlive = true;

        // Initialize role-specific state
        if (role == Role.WITCH) {
            this.witchHasHealPotion = true;
            this.witchHasPoisonPotion = true;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public ReActAgent getAgent() {
        return agent;
    }

    public String getName() {
        return name;
    }

    public Role getRole() {
        return role;
    }

    public boolean isAlive() {
        return isAlive;
    }

    public boolean isWitchHasHealPotion() {
        return witchHasHealPotion;
    }

    public boolean isWitchHasPoisonPotion() {
        return witchHasPoisonPotion;
    }

    // State modifiers
    public void kill() {
        this.isAlive = false;
    }

    public void resurrect() {
        this.isAlive = true;
    }

    public void useHealPotion() {
        this.witchHasHealPotion = false;
    }

    public void usePoisonPotion() {
        this.witchHasPoisonPotion = false;
    }

    @Override
    public String toString() {
        String status = isAlive ? "alive" : "dead";
        return String.format("%s (%s, %s)", name, role.getDisplayName(), status);
    }

    public static class Builder {
        private ReActAgent agent;
        private String name;
        private Role role;

        public Builder agent(ReActAgent agent) {
            this.agent = agent;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder role(Role role) {
            this.role = role;
            return this;
        }

        public Player build() {
            if (agent == null || name == null || role == null) {
                throw new IllegalStateException("Agent, name, and role are required");
            }
            return new Player(this);
        }
    }
}
