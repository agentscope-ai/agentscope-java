/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.examples.bobatea.consult.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Product Entity Class
 */
public class Product {

    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
    private Integer shelfTime;
    private Integer preparationTime;
    private Boolean isSeasonal;
    private LocalDateTime seasonStart;
    private LocalDateTime seasonEnd;
    private Boolean isRegional;
    private String availableRegions;
    private Boolean status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Product() {}

    public Product(
            Long id,
            String name,
            String description,
            BigDecimal price,
            Integer stock,
            Integer shelfTime,
            Integer preparationTime,
            Boolean isSeasonal,
            LocalDateTime seasonStart,
            LocalDateTime seasonEnd,
            Boolean isRegional,
            String availableRegions,
            Boolean status) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.shelfTime = shelfTime;
        this.preparationTime = preparationTime;
        this.isSeasonal = isSeasonal;
        this.seasonStart = seasonStart;
        this.seasonEnd = seasonEnd;
        this.isRegional = isRegional;
        this.availableRegions = availableRegions;
        this.status = status;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public Integer getShelfTime() {
        return shelfTime;
    }

    public void setShelfTime(Integer shelfTime) {
        this.shelfTime = shelfTime;
    }

    public Integer getPreparationTime() {
        return preparationTime;
    }

    public void setPreparationTime(Integer preparationTime) {
        this.preparationTime = preparationTime;
    }

    public Boolean getIsSeasonal() {
        return isSeasonal;
    }

    public void setIsSeasonal(Boolean isSeasonal) {
        this.isSeasonal = isSeasonal;
    }

    public LocalDateTime getSeasonStart() {
        return seasonStart;
    }

    public void setSeasonStart(LocalDateTime seasonStart) {
        this.seasonStart = seasonStart;
    }

    public LocalDateTime getSeasonEnd() {
        return seasonEnd;
    }

    public void setSeasonEnd(LocalDateTime seasonEnd) {
        this.seasonEnd = seasonEnd;
    }

    public Boolean getIsRegional() {
        return isRegional;
    }

    public void setIsRegional(Boolean isRegional) {
        this.isRegional = isRegional;
    }

    public String getAvailableRegions() {
        return availableRegions;
    }

    public void setAvailableRegions(String availableRegions) {
        this.availableRegions = availableRegions;
    }

    public Boolean getStatus() {
        return status;
    }

    public void setStatus(Boolean status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "Product{"
                + "id="
                + id
                + ", name='"
                + name
                + '\''
                + ", description='"
                + description
                + '\''
                + ", price="
                + price
                + ", stock="
                + stock
                + ", shelfTime="
                + shelfTime
                + ", preparationTime="
                + preparationTime
                + ", isSeasonal="
                + isSeasonal
                + ", seasonStart="
                + seasonStart
                + ", seasonEnd="
                + seasonEnd
                + ", isRegional="
                + isRegional
                + ", availableRegions='"
                + availableRegions
                + '\''
                + ", status="
                + status
                + ", createdAt="
                + createdAt
                + ", updatedAt="
                + updatedAt
                + '}';
    }
}
