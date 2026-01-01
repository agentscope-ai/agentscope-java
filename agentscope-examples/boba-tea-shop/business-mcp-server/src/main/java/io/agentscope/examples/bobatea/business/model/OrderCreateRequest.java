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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Create Order Request DTO
 */
public class OrderCreateRequest {

    @NotNull(message = "User ID cannot be empty")
    private Long userId;

    private Long productId;

    @NotBlank(message = "Product name cannot be empty")
    private String productName;

    @NotNull(message = "Sweetness cannot be empty")
    @Min(value = 1, message = "Sweetness value must be between 1-5")
    @Max(value = 5, message = "Sweetness value must be between 1-5")
    private Integer sweetness;

    @NotNull(message = "Ice level cannot be empty")
    @Min(value = 1, message = "Ice level value must be between 1-5")
    @Max(value = 5, message = "Ice level value must be between 1-5")
    private Integer iceLevel;

    @NotNull(message = "Quantity cannot be empty")
    @Min(value = 1, message = "Quantity must be greater than 0")
    private Integer quantity;

    private String remark;

    // Constructor
    public OrderCreateRequest() {}

    public OrderCreateRequest(
            Long userId,
            Long productId,
            String productName,
            Integer sweetness,
            Integer iceLevel,
            Integer quantity,
            String remark) {
        this.userId = userId;
        this.productId = productId;
        this.productName = productName;
        this.sweetness = sweetness;
        this.iceLevel = iceLevel;
        this.quantity = quantity;
        this.remark = remark;
    }

    // Getter and Setter methods
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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    @Override
    public String toString() {
        return "OrderCreateRequest{"
                + "userId="
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
                + ", remark='"
                + remark
                + '\''
                + '}';
    }
}
