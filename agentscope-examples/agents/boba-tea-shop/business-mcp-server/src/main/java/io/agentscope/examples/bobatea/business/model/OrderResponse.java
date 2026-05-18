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

package io.agentscope.examples.bobatea.business.model;

import io.agentscope.examples.bobatea.business.entity.Order;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Order Response DTO
 */
public class OrderResponse {

    private Long id;
    private String orderId;
    private Long userId;
    private Long productId;
    private String productName;
    private Integer sweetness;
    private String sweetnessText;
    private Integer iceLevel;
    private String iceLevelText;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Constructor
    public OrderResponse() {}

    public OrderResponse(Order order) {
        this.id = order.getId();
        this.orderId = order.getOrderId();
        this.userId = order.getUserId();
        this.productId = order.getProductId();
        this.productName = order.getProductName();
        this.sweetness = order.getSweetness();
        this.sweetnessText = order.getSweetnessText();
        this.iceLevel = order.getIceLevel();
        this.iceLevelText = order.getIceLevelText();
        this.quantity = order.getQuantity();
        this.unitPrice = order.getUnitPrice();
        this.totalPrice = order.getTotalPrice();
        this.remark = order.getRemark();
        this.createdAt = order.getCreatedAt();
        this.updatedAt = order.getUpdatedAt();
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

    public String getSweetnessText() {
        return sweetnessText;
    }

    public void setSweetnessText(String sweetnessText) {
        this.sweetnessText = sweetnessText;
    }

    public Integer getIceLevel() {
        return iceLevel;
    }

    public void setIceLevel(Integer iceLevel) {
        this.iceLevel = iceLevel;
    }

    public String getIceLevelText() {
        return iceLevelText;
    }

    public void setIceLevelText(String iceLevelText) {
        this.iceLevelText = iceLevelText;
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

    @Override
    public String toString() {
        return "OrderResponse{"
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
                + ", sweetnessText='"
                + sweetnessText
                + '\''
                + ", iceLevel="
                + iceLevel
                + ", iceLevelText='"
                + iceLevelText
                + '\''
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
