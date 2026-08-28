/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web.dto;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * A page of anything, in the shape the contract describes.
 *
 * <p>Spring Data's {@code Page} serialises to a large, unstable structure that leaks the
 * repository's own vocabulary — {@code pageable}, {@code sort}, {@code numberOfElements}, {@code
 * first}, {@code last} — and its serialised form has changed between Spring Data versions. The
 * contract names four fields, so four fields is what goes on the wire.
 *
 * <p>{@code totalElements} is a real count and not an estimate. If it ever becomes too costly the
 * contract says the answer is keyset paging in a new version, rather than the same field quietly
 * becoming approximate.
 */
public record PageResponse<T>(List<T> content, PageMeta page) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                new PageMeta(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }

    /** Paging metadata, matching the contract's {@code PageMeta}. */
    public record PageMeta(int page, int size, long totalElements, int totalPages) {}
}
