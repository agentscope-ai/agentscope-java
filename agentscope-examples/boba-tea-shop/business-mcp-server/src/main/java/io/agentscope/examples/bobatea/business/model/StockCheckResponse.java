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

/**
 * Response DTO for stock check
 */
public class StockCheckResponse {

    private String productName;
    private int quantity;
    private boolean available;

    public StockCheckResponse() {}

    public StockCheckResponse(String productName, int quantity, boolean available) {
        this.productName = productName;
        this.quantity = quantity;
        this.available = available;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public String toString() {
        return "StockCheckResponse{"
                + "productName='"
                + productName
                + '\''
                + ", quantity="
                + quantity
                + ", available="
                + available
                + '}';
    }
}
