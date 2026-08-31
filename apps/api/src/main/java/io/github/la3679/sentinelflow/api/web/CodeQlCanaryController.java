/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEMPORARY. Deliberately vulnerable, to prove the CodeQL Java extractor sees this source tree.
 *
 * <p>This file is never merged. It exists for one push, to answer a question a green run cannot: a
 * CodeQL analysis over an empty database reports zero results and looks exactly like a clean one.
 */
@RestController
public class CodeQlCanaryController {

    private final EntityManager entityManager;

    public CodeQlCanaryController(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @GetMapping("/api/v1/codeql-canary")
    @SuppressWarnings("unchecked")
    List<Object> canary(@RequestParam String reference) {
        // Textbook CWE-89: a request parameter concatenated into a native query.
        return entityManager
                .createNativeQuery("SELECT id FROM accounts WHERE account_reference = '" + reference + "'")
                .getResultList();
    }
}
