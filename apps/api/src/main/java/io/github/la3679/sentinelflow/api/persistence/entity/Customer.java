/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.github.la3679.sentinelflow.api.domain.CustomerStatus;
import io.github.la3679.sentinelflow.api.domain.RiskTier;

/**
 * A synthetic customer.
 *
 * <p>No name, no address, no date of birth, no identifier issued by anyone. A fraud rule reads a
 * country and a risk tier; it does not read a name, and storing personal data this system has no
 * use for would be the failure rather than the omission.
 *
 * <p>{@code customerReference} is a human-readable handle for a support conversation. It is
 * unique, and it is never a foreign key (ADR-0007).
 */
@Entity
@Table(name = "customers")
public class Customer extends AbstractEntity {

    @Column(name = "customer_reference", nullable = false, length = 16, updatable = false)
    private String customerReference;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tier", nullable = false, length = 16)
    private RiskTier riskTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CustomerStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {}

    public Customer(String customerReference, String countryCode, RiskTier riskTier, CustomerStatus status) {
        this.customerReference = customerReference;
        this.countryCode = countryCode;
        this.riskTier = riskTier;
        this.status = status;
    }

    public String getCustomerReference() {
        return customerReference;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public RiskTier getRiskTier() {
        return riskTier;
    }

    public void setRiskTier(RiskTier riskTier) {
        this.riskTier = riskTier;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public void setStatus(CustomerStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
