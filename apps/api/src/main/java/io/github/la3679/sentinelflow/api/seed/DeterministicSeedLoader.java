/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.domain.AccountStatus;
import io.github.la3679.sentinelflow.api.domain.CustomerStatus;
import io.github.la3679.sentinelflow.api.domain.Money;
import io.github.la3679.sentinelflow.api.domain.RiskTier;
import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.domain.UserStatus;
import io.github.la3679.sentinelflow.api.persistence.entity.Account;
import io.github.la3679.sentinelflow.api.persistence.entity.Customer;
import io.github.la3679.sentinelflow.api.persistence.entity.Merchant;
import io.github.la3679.sentinelflow.api.persistence.entity.User;
import io.github.la3679.sentinelflow.api.persistence.entity.UserRole;

/**
 * Loads the synthetic parties a demo needs: customers, their accounts, the merchants they transact
 * with, and a handful of analyst logins.
 *
 * <p><strong>Application code, never a migration.</strong> A migration is schema, it is immutable
 * once merged, and it runs in every environment including ones that must stay empty. Demo data is
 * none of those things. The only rows a migration inserts are the roles and the system principal,
 * which the schema's own foreign keys depend on.
 *
 * <p><strong>Deterministic.</strong> Everything varying is drawn from a single {@link Random} seeded
 * from configuration. {@code java.util.Random} is specified exactly by the JDK rather than left to
 * the implementation, so the same seed produces the same dataset on any machine and any JDK - which
 * is what makes "reproduce it with seed 20260826" a usable instruction rather than a hope.
 *
 * <p><strong>Idempotent.</strong> A second run over a database that already holds demo customers
 * writes nothing and says so. Loading twice must not double the dataset, and must not fail either:
 * a developer who runs it again is asking for the data to be there, not for an error.
 *
 * <p><strong>Synthetic, and not merely anonymised.</strong> There are no names, addresses, dates of
 * birth, national identifiers or card numbers here, because the schema has nowhere to put them.
 * Merchant names are assembled from two invented word lists and match no real business. Every
 * reference follows the documented {@code CUS-}, {@code ACC-} and {@code MER-} patterns, which are
 * unmistakably not a real institution's numbering.
 *
 * <p>Transactions, scenarios and labelled suspicious patterns are Phase 4 and are generated on top
 * of these parties.
 */
@Service
public class DeterministicSeedLoader {

    private static final Logger log = LoggerFactory.getLogger(DeterministicSeedLoader.class);

    /** Invented, and deliberately not evocative of any trading name. */
    private static final List<String> MERCHANT_PREFIXES = List.of(
            "Aster",
            "Bramble",
            "Cinder",
            "Dovetail",
            "Ember",
            "Fernwick",
            "Glimmer",
            "Harrow",
            "Ivory",
            "Juniper",
            "Kestrel",
            "Larkspur",
            "Marrow",
            "Nettle",
            "Oriel",
            "Pewter",
            "Quarry",
            "Rushlight",
            "Sable",
            "Thistle");

    private static final List<String> MERCHANT_SUFFIXES =
            List.of("Provisions", "Outfitters", "Works", "Exchange", "Depot", "Traders", "Supply", "Collective");

    /** ISO 18245 categories, chosen to span the ones an unusual-category rule cares about. */
    private static final List<String> CATEGORY_CODES =
            List.of("5411", "5812", "5999", "4121", "7995", "6011", "5732", "0742");

    /** ISO 3166-1 alpha-2. A country code is a jurisdiction, not a person. */
    private static final List<String> COUNTRIES = List.of("GB", "IE", "FR", "DE", "ES", "NL", "PL", "US", "CA", "AU");

    private static final List<String> CURRENCIES = List.of("GBP", "EUR", "USD");

    private static final List<RiskTier> RISK_TIERS = List.of(RiskTier.STANDARD, RiskTier.ENHANCED, RiskTier.HIGH);

    /** Fixed logins so a demo script can refer to one by name across runs. */
    private static final List<String> ANALYST_USERNAMES =
            List.of("analyst.one", "analyst.two", "administrator.one", "auditor.one");

    private final EntityManager entityManager;

    public DeterministicSeedLoader(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Loads the profile's parties, or reports that they were already there.
     *
     * @return what was written, with a checksum over the generated references
     */
    @Transactional
    public SeedManifest load(SeedProperties properties) {
        SeedProfile profile = properties.profile();

        if (alreadySeeded()) {
            log.info("Demo data already present; seed skipped. Use `make reset-demo` to start from empty.");
            return SeedManifest.skipped(properties.seed(), profile);
        }

        Random random = new Random(properties.seed());
        // Accumulated in generation order and hashed at the end. Order is part
        // of the claim: two runs that produced the same references in a
        // different order did not produce the same dataset.
        List<String> references = new ArrayList<>();

        List<Merchant> merchants = seedMerchants(profile, random, references);
        List<Customer> customers = seedCustomers(profile, random, references);
        int accounts = seedAccounts(customers, profile, random, references);
        int analysts = seedAnalysts(references);

        SeedManifest manifest = new SeedManifest(
                SeedManifest.GENERATOR_VERSION,
                properties.seed(),
                profile,
                customers.size(),
                merchants.size(),
                accounts,
                analysts,
                checksumOf(references),
                false);

        log.info(
                "Seeded {} synthetic rows from seed {} at profile {} (checksum {}). All data is synthetic.",
                manifest.totalRows(),
                manifest.seed(),
                profile,
                manifest.checksum());
        return manifest;
    }

    private boolean alreadySeeded() {
        Long existing = entityManager
                .createQuery("SELECT count(c) FROM Customer c", Long.class)
                .getSingleResult();
        return existing > 0;
    }

    private List<Merchant> seedMerchants(SeedProfile profile, Random random, List<String> references) {
        List<Merchant> merchants = new ArrayList<>(profile.merchants());
        for (int i = 1; i <= profile.merchants(); i++) {
            String reference = "MER-%04d".formatted(i);
            String name = "%s %s".formatted(pick(MERCHANT_PREFIXES, random), pick(MERCHANT_SUFFIXES, random));
            Merchant merchant = new Merchant(reference, name, pick(CATEGORY_CODES, random), pick(COUNTRIES, random));
            entityManager.persist(merchant);
            merchants.add(merchant);
            references.add(reference);
        }
        return merchants;
    }

    private List<Customer> seedCustomers(SeedProfile profile, Random random, List<String> references) {
        List<Customer> customers = new ArrayList<>(profile.customers());
        for (int i = 1; i <= profile.customers(); i++) {
            String reference = "CUS-%06d".formatted(i);
            Customer customer = new Customer(
                    reference,
                    pick(COUNTRIES, random),
                    // Weighted towards STANDARD: a population where a third of
                    // customers are HIGH risk teaches a demo the wrong thing
                    // about what a risk tier means.
                    random.nextInt(10) < 7 ? RiskTier.STANDARD : pick(RISK_TIERS, random),
                    CustomerStatus.ACTIVE);
            entityManager.persist(customer);
            customers.add(customer);
            references.add(reference);
        }
        return customers;
    }

    private int seedAccounts(List<Customer> customers, SeedProfile profile, Random random, List<String> references) {
        int accountNumber = 0;
        Instant openedFloor = Instant.parse("2024-01-01T00:00:00Z");

        for (Customer customer : customers) {
            for (int i = 0; i < profile.accountsPerCustomer(); i++) {
                String reference = "ACC-%06d".formatted(++accountNumber);
                // Two decimal places, well inside NUMERIC(19,4), and never
                // produced by floating point. nextInt(int) rather than
                // nextInt(origin, bound): only the single-argument form is
                // specified exactly by java.util.Random, and the determinism
                // claim above depends on that specification rather than on one
                // JDK's default method.
                BigDecimal balance = BigDecimal.valueOf(50L + random.nextInt(499_950), 2);
                Account account = new Account(
                        customer.getId(),
                        reference,
                        Money.of(balance, pick(CURRENCIES, random)),
                        AccountStatus.ACTIVE,
                        openedFloor.plus(random.nextInt(700), ChronoUnit.DAYS));
                entityManager.persist(account);
                references.add(reference);
            }
        }
        return accountNumber;
    }

    private int seedAnalysts(List<String> references) {
        // Not drawn from the random source: these logins are fixed so a demo
        // script can name one and still find it after the seed changes.
        for (String username : ANALYST_USERNAMES) {
            RoleCode role = roleFor(username);
            User user = new User(username, displayNameFor(username), UserStatus.ACTIVE);
            entityManager.persist(user);
            entityManager.persist(new UserRole(user.getId(), roleId(role)));
            references.add(username);
        }
        return ANALYST_USERNAMES.size();
    }

    private static RoleCode roleFor(String username) {
        if (username.startsWith("administrator")) {
            return RoleCode.ADMINISTRATOR;
        }
        if (username.startsWith("auditor")) {
            return RoleCode.AUDITOR;
        }
        return RoleCode.ANALYST;
    }

    /** A label, not a person. "Analyst One" is nobody. */
    private static String displayNameFor(String username) {
        String[] parts = username.split("\\.");
        StringBuilder display = new StringBuilder();
        for (String part : parts) {
            if (!display.isEmpty()) {
                display.append(' ');
            }
            display.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return display.toString();
    }

    private UUID roleId(RoleCode code) {
        return entityManager
                .createQuery("SELECT r.id FROM Role r WHERE r.code = :code", UUID.class)
                .setParameter("code", code)
                .getSingleResult();
    }

    private static <T> T pick(List<T> candidates, Random random) {
        return candidates.get(random.nextInt(candidates.size()));
    }

    private static String checksumOf(List<String> references) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String reference : references) {
                digest.update(reference.getBytes(StandardCharsets.UTF_8));
                // A separator, so ["AB","C"] and ["A","BC"] do not hash alike.
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every conformant JRE. If it is missing,
            // something is wrong that a fallback would only hide.
            throw new IllegalStateException("SHA-256 is unavailable in this JRE", e);
        }
    }
}
