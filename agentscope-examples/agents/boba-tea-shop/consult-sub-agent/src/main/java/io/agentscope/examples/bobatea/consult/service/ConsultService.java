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

package io.agentscope.examples.bobatea.consult.service;

import io.agentscope.examples.bobatea.consult.entity.Product;
import io.agentscope.examples.bobatea.consult.mapper.ProductMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Consultation Knowledge Base Service Class
 * Provides retrieval services for bubble tea shop products and store information
 */
@Service
public class ConsultService {

    private static final Logger logger = LoggerFactory.getLogger(ConsultService.class);

    @Autowired private ProductMapper productMapper;

    /**
     * Get all available product list
     */
    public List<Product> getAllProducts() {
        logger.info("=== ConsultService.getAllProducts entry ===");

        try {
            List<Product> products = productMapper.selectAllAvailable();

            logger.info("=== ConsultService.getAllProducts exit ===");
            logger.info("Return result - total products: {}", products.size());

            return products;
        } catch (Exception e) {
            logger.error("Get product list exception", e);
            return new ArrayList<>();
        }
    }

    /**
     * Get product details by product name
     */
    public Product getProductByName(String productName) {
        logger.info("=== ConsultService.getProductByName entry ===");
        logger.info("Request parameter - productName: {}", productName);

        try {
            Product product = productMapper.selectByNameAndStatus(productName, 1);

            logger.info("=== ConsultService.getProductByName exit ===");
            logger.info(
                    "Return result - product: {}", product != null ? product.getName() : "null");

            return product;
        } catch (Exception e) {
            logger.error("Get product details exception", e);
            return null;
        }
    }

    /**
     * Fuzzy search product list by product name
     */
    public List<Product> searchProductsByName(String productName) {
        logger.info("=== ConsultService.searchProductsByName entry ===");
        logger.info("Request parameter - productName: {}", productName);

        try {
            List<Product> products = productMapper.selectByNameLike(productName);

            logger.info("=== ConsultService.searchProductsByName exit ===");
            logger.info("Return result - total products: {}", products.size());

            return products;
        } catch (Exception e) {
            logger.error("Search products exception", e);
            return new ArrayList<>();
        }
    }

    /**
     * Validate if product exists and is available
     */
    public boolean validateProduct(String productName) {
        logger.info("=== ConsultService.validateProduct entry ===");
        logger.info("Request parameter - productName: {}", productName);

        try {
            boolean exists = productMapper.existsByNameAndStatusTrue(productName) > 0;

            logger.info("=== ConsultService.validateProduct exit ===");
            logger.info("Return result - exists: {}", exists);

            return exists;
        } catch (Exception e) {
            logger.error("Validate product exception", e);
            return false;
        }
    }
}
