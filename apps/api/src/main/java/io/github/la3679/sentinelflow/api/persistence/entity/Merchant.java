/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A synthetic counterparty.
 *
 * <p>Names here are invented and match no real business. The category code is ISO 18245, kept as
 * text rather than an integer because its leading zero is significant - {@code 0742} and {@code
 * 742} are not the same category, and an integer column cannot tell them apart.
 */
@Entity
@Table(name = "merchants")
public class Merchant extends AbstractEntity {

    @Column(name = "merchant_reference", nullable = false, length = 16, updatable = false)
    private String merchantReference;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "category_code", nullable = false, length = 4)
    private String categoryCode;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Merchant() {}

    public Merchant(String merchantReference, String name, String categoryCode, String countryCode) {
        this.merchantReference = merchantReference;
        this.name = name;
        this.categoryCode = categoryCode;
        this.countryCode = countryCode;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public String getName() {
        return name;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
