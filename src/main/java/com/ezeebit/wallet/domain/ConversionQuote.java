package com.ezeebit.wallet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A short-lived, locked-in price for a currency conversion. The merchant
 * fetches a quote, then executes against the quote id (not "the current
 * market rate"). This is what stops the platform ending up on the wrong
 * side of a moving market: the rate is fixed the moment the quote is
 * created, and the quote expires quickly (see ezeebit.conversion.quote-ttl-seconds)
 * so Ezeebit's own exposure to that fixed rate is bounded and short.
 */
@Entity
@Table(name = "conversion_quotes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversionQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private String publicId = UUID.randomUUID().toString();

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "from_currency", nullable = false, length = 10)
    private String fromCurrency;

    @Column(name = "to_currency", nullable = false, length = 10)
    private String toCurrency;

    @Column(name = "from_amount_minor", nullable = false)
    private long fromAmountMinor;

    @Column(name = "to_amount_minor", nullable = false)
    private long toAmountMinor;

    @Column(nullable = false, precision = 24, scale = 10)
    private BigDecimal rate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuoteStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public enum QuoteStatus { ACTIVE, USED, EXPIRED }
}
