/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.la3679.sentinelflow.api.persistence.entity.UserCredential;

/** Login credentials, keyed by the user they belong to. */
public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {}
