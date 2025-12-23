/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
 * 订单实体类
 */
public class Order {

    private Long id;

    @NotBlank(message = "订单编号不能为空")
    private String orderId;

    private Long userId;

    @NotNull(message = "产品ID不能为空")
    private Long productId;

    @NotBlank(message = "产品名称不能为空")
    private String productName;

    @Min(value = 1, message = "甜度值必须在1-5之间")
    @Max(value = 5, message = "甜度值必须在1-5之间")
    private Integer sweetness;

    @Min(value = 1, message = "冰量值必须在1-5之间")
    @Max(value = 5, message = "冰量值必须在1-5之间")
    private Integer iceLevel;

    @Min(value = 1, message = "数量必须大于0")
    private Integer quantity;

    @NotNull(message = "单价不能为空")
    @DecimalMin(value = "0.01", message = "单价必须大于0")
    private BigDecimal unitPrice;

    @NotNull(message = "总价不能为空")
    @DecimalMin(value = "0.01", message = "总价必须大于0")
    private BigDecimal totalPrice;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // 构造函数
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

    // 生命周期回调方法
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getter和Setter方法
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

    // 甜度枚举转换方法
    public String getSweetnessText() {
        if (sweetness == null) return "未知";
        switch (sweetness) {
            case 1:
                return "无糖";
            case 2:
                return "微糖";
            case 3:
                return "半糖";
            case 4:
                return "少糖";
            case 5:
                return "标准糖";
            default:
                return "未知";
        }
    }

    // 冰量枚举转换方法
    public String getIceLevelText() {
        if (iceLevel == null) return "未知";
        switch (iceLevel) {
            case 1:
                return "热";
            case 2:
                return "温";
            case 3:
                return "去冰";
            case 4:
                return "少冰";
            case 5:
                return "正常冰";
            default:
                return "未知";
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
