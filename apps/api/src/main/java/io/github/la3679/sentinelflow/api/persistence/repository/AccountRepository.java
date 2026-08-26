/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.la3679.sentinelflow.api.persistence.entity.Account;

/** Accounts, looked up the way a request names them. */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountReference(String accountReference);
}
