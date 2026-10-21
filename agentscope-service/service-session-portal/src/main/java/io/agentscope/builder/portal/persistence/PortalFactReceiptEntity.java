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
package io.agentscope.builder.portal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Source deduplication, public sequence and quota effects commit in one Portal transaction. */
@Entity
@Table(
        name = "builder_portal_fact_receipt",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_portal_fact_source_sequence",
                        columnNames = {"source_id", "source_sequence"}))
public class PortalFactReceiptEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "source_id", nullable = false, length = 64)
    public String sourceId;

    @Column(name = "source_sequence", nullable = false)
    public long sourceSequence;

    @Column(nullable = false, length = 64)
    public String digest;

    @Column(name = "public_sequence", nullable = false)
    public long publicSequence;
}
