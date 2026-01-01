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

package io.agentscope.examples.bobatea.business.entity;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Order Entity Class
 */
public class Order {

    private Long id;

    @NotBlank(message = "Order ID cannot be empty")
    private String orderId;

    private Long userId;

    @NotNull(message = "Product ID cannot be empty")
    private Long productId;

    @NotBlank(message = "Product name cannot be empty")
    private String productName;

    @Min(value = 1, message = "Sweetness value must be between 1-5")
    @Max(value = 5, message = "Sweetness value must be between 1-5")
    private Integer sweetness;

    @Min(value = 1, message = "Ice level value must be between 1-5")
    @Max(value = 5, message = "Ice level value must be between 1-5")
    private Integer iceLevel;

    @Min(value = 1, message = "Quantity must be greater than 0")
    private Integer quantity;

    @NotNull(message = "Unit price cannot be empty")
    @DecimalMin(value = "0.01", message = "Unit price must be greater than 0")
    private BigDecimal unitPrice;

    @NotNull(message = "Total price cannot be empty")
    @DecimalMin(value = "0.01", message = "Total price must be greater than 0")
    private BigDecimal totalPrice;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Constructor
    public Order() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Order(
            String orderId,
            Long userId,
            Long productId,
            String productName,
            Integer sweetness,
            Integer iceLevel,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice,
            String remark) {
        this();
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.productName = productName;
        this.sweetness = sweetness;
        this.iceLevel = iceLevel;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalPrice = totalPrice;
        this.remark = remark;
    }

    // Lifecycle callback methods
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getter and Setter methods
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Integer getSweetness() {
        return sweetness;
    }

    public void setSweetness(Integer sweetness) {
        this.sweetness = sweetness;
    }

    public Integer getIceLevel() {
        return iceLevel;
    }

    public void setIceLevel(Integer iceLevel) {
        this.iceLevel = iceLevel;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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

    // Sweetness enum conversion method
    public String getSweetnessText() {
        if (sweetness == null) return "Unknown";
        switch (sweetness) {
            case 1:
                return "No Sugar";
            case 2:
                return "Light Sugar";
            case 3:
                return "Half Sugar";
            case 4:
                return "Less Sugar";
            case 5:
                return "Regular Sugar";
            default:
                return "Unknown";
        }
    }

    // Ice level enum conversion method
    public String getIceLevelText() {
        if (iceLevel == null) return "Unknown";
        switch (iceLevel) {
            case 1:
                return "Hot";
            case 2:
                return "Warm";
            case 3:
                return "No Ice";
            case 4:
                return "Less Ice";
            case 5:
                return "Regular Ice";
            default:
                return "Unknown";
        }
    }

    @Override
    public String toString() {
        return "Order{"
                + "id="
                + id
                + ", orderId='"
                + orderId
                + '\''
                + ", userId="
                + userId
                + ", productId="
                + productId
                + ", productName='"
                + productName
                + '\''
                + ", sweetness="
                + sweetness
                + ", iceLevel="
                + iceLevel
                + ", quantity="
                + quantity
                + ", unitPrice="
                + unitPrice
                + ", totalPrice="
                + totalPrice
                + ", remark='"
                + remark
                + '\''
                + ", createdAt="
                + createdAt
                + ", updatedAt="
                + updatedAt
                + '}';
    }
}
