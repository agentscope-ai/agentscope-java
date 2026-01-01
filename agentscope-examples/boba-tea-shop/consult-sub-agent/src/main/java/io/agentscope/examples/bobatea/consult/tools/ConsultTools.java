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

package io.agentscope.examples.bobatea.consult.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.bobatea.consult.entity.Product;
import io.agentscope.examples.bobatea.consult.service.ConsultService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Consultation Knowledge Base MCP Tool Class
 * Provides knowledge base retrieval tools under MCP protocol
 */
@Service
public class ConsultTools {

    @Autowired private ConsultService consultService;

    /**
     * Get all products list tool
     */
    @Tool(
            name = "consult-get-products",
            description =
                    "Get the complete list of all available products at Cloud Edge Boba Tea Shop,"
                        + " including product name, detailed description, current price and stock"
                        + " quantity. Helps users understand the available boba tea products.")
    public String getProducts() {
        try {
            List<Product> products = consultService.getAllProducts();
            if (products.isEmpty()) {
                return "No products available at the moment.";
            }

            StringBuilder result =
                    new StringBuilder("Cloud Edge Boba Tea Shop available products:\n");
            for (Product product : products) {
                result.append(
                        String.format(
                                "- %s: %s, Price: %.2f yuan, Stock: %d units\n",
                                product.getName(),
                                product.getDescription(),
                                product.getPrice(),
                                product.getStock()));
            }

            return result.toString();
        } catch (Exception e) {
            return "Failed to get product list: " + e.getMessage();
        }
    }

    /**
     * Get product detailed information tool
     */
    @Tool(
            name = "consult-get-product-info",
            description =
                    "Get detailed information about a specified product, including product"
                        + " description, price and current stock status. Helps users understand the"
                        + " specific product information.")
    public String getProductInfo(
            @ToolParam(
                            name = "productName",
                            description =
                                    "Product name, must be an existing product at Cloud Edge Boba"
                                        + " Tea Shop, such as: Cloud Jasmine, Osmanthus Cloud Dew,"
                                        + " Misty Tieguanyin, Mountain Red Charm, Cloud Peach"
                                        + " Oolong, Cloud Edge Pu'er, Osmanthus Longjing, Cloud"
                                        + " Peak Mountain Tea")
                    String productName) {
        try {
            Product product = consultService.getProductByName(productName);
            if (product == null) {
                return "Product does not exist or has been discontinued: " + productName;
            }

            return String.format(
                    "Product Information:\n"
                            + "Name: %s\n"
                            + "Description: %s\n"
                            + "Price: %.2f yuan\n"
                            + "Stock: %d units\n"
                            + "Shelf Life: %d minutes\n"
                            + "Preparation Time: %d minutes",
                    product.getName(),
                    product.getDescription(),
                    product.getPrice(),
                    product.getStock(),
                    product.getShelfTime(),
                    product.getPreparationTime());
        } catch (Exception e) {
            return "Failed to get product information: " + e.getMessage();
        }
    }

    /**
     * Fuzzy search products by product name tool
     */
    @Tool(
            name = "consult-search-products",
            description =
                    "Fuzzy search by product name, returns a list of matching products. Supports"
                            + " partial name search, for example searching 'Cloud' can find all"
                            + " products containing the word 'Cloud'.")
    public String searchProducts(
            @ToolParam(
                            name = "productName",
                            description =
                                    "Product name keyword, supports fuzzy matching, for example:"
                                            + " Cloud, Jasmine, Oolong, etc.")
                    String productName) {
        try {
            List<Product> products = consultService.searchProductsByName(productName);
            if (products.isEmpty()) {
                return "No matching products found: " + productName;
            }

            StringBuilder result =
                    new StringBuilder("Search results (" + products.size() + " products):\n");
            for (Product product : products) {
                result.append(
                        String.format(
                                "- %s: %s, Price: %.2f yuan, Stock: %d units\n",
                                product.getName(),
                                product.getDescription(),
                                product.getPrice(),
                                product.getStock()));
            }

            return result.toString();
        } catch (Exception e) {
            return "Failed to search products: " + e.getMessage();
        }
    }
}
