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

import io.agentscope.core.agent.AgentBase;

/** Represents a player in the Werewolf game. */
public class Player {
    private final AgentBase agent;
    private final String name;
    private final Role role;
    private final boolean isHuman;
    private boolean isAlive;

    // Role-specific state
    private boolean witchHasHealPotion;
    private boolean witchHasPoisonPotion;

    // Sheriff election related state
    private boolean registeredForSheriff; // 是否上警竞选警长
    private boolean isSheriff; // 是否是警长
    private String nextCheckTarget; // 预言家下一个要验证的玩家（用于警徽传递信息）

    private Player(Builder builder) {
        this.agent = builder.agent;
        this.name = builder.name;
        this.role = builder.role;
        this.isHuman = builder.isHuman;
        this.isAlive = true;

        // Initialize role-specific state
        if (role == Role.WITCH) {
            this.witchHasHealPotion = true;
            this.witchHasPoisonPotion = true;
        }
        this.registeredForSheriff = false;
        this.isSheriff = false;
        this.nextCheckTarget = null;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public AgentBase getAgent() {
        return agent;
    }

    public String getName() {
        return name;
    }

    public Role getRole() {
        return role;
    }

    public boolean isHuman() {
        return isHuman;
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

    public boolean isRegisteredForSheriff() {
        return registeredForSheriff;
    }

    public boolean isSheriff() {
        return isSheriff;
    }

    public String getNextCheckTarget() {
        return nextCheckTarget;
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

    public void registerForSheriff() {
        this.registeredForSheriff = true;
    }

    public void setSheriff(boolean sheriff) {
        this.isSheriff = sheriff;
    }

    public void setNextCheckTarget(String targetName) {
        this.nextCheckTarget = targetName;
    }

    @Override
    public String toString() {
        String status = isAlive ? "alive" : "dead";
        String humanStatus = isHuman ? " [HUMAN]" : "";
        return String.format("%s (%s, %s)%s", name, role.getDisplayName(), status, humanStatus);
    }

    public static class Builder {
        private AgentBase agent;
        private String name;
        private Role role;
        private boolean isHuman = false;

        public Builder agent(AgentBase agent) {
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

        public Builder isHuman(boolean isHuman) {
            this.isHuman = isHuman;
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
