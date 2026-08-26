/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import io.github.la3679.sentinelflow.api.domain.AccountStatus;
import io.github.la3679.sentinelflow.api.domain.Money;

/**
 * A synthetic account, and the balance movements are assessed against.
 *
 * <p><strong>The owning customer is a {@link UUID}, not a {@code @ManyToOne}.</strong> That is the
 * rule everywhere in this package. An association gives Hibernate a proxy to initialise on every
 * traversal, turns "the account this transaction belongs to" into an implicit query nobody wrote,
 * and makes the N+1 the default rather than the mistake. The identifier is available in the
 * constructor (see {@link AbstractEntity}), so a reference never needs a loaded object to exist,
 * and "the customer behind this account" stays an explicit repository call with a bound on it.
 *
 * <p><strong>The balance is {@link Money}, embedded with an override.</strong> The column is {@code
 * balance} rather than {@code amount}, and the currency travels with the value, so an account
 * balance cannot exist without a currency. {@link #setBalance} rejects a different one: an account
 * does not change denomination, and permitting it would silently reinterpret every past amount.
 */
@Entity
@Table(name = "accounts")
public class Account extends AbstractEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "account_reference", nullable = false, length = 16, updatable = false)
    private String accountReference;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "balance", nullable = false, precision = 19, scale = 4))
    @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3))
    private Money balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {}

    public Account(UUID customerId, String accountReference, Money balance, AccountStatus status, Instant openedAt) {
        this.customerId = customerId;
        this.accountReference = accountReference;
        this.balance = balance;
        this.status = status;
        this.openedAt = openedAt;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getAccountReference() {
        return accountReference;
    }

    public Money getBalance() {
        return balance;
    }

    /**
     * @throws IllegalArgumentException if the new balance is in a different currency
     */
    public void setBalance(Money balance) {
        if (!this.balance.currency().equals(balance.currency())) {
            throw new IllegalArgumentException("Account " + accountReference + " is denominated in "
                    + this.balance.currency() + " and cannot hold " + balance.currency());
        }
        this.balance = balance;
    }

    public String getCurrency() {
        return balance.currency();
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
