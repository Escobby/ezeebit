package com.ezeebit.wallet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One payout request. The payout rail is asynchronous (see PayoutRailClient):
 * we ask it to pay out, funds are reserved immediately, and some time later a
 * callback tells us whether it succeeded or failed.
 *
 * PENDING     - funds have been reserved (debited) from the merchant's balance,
 *               the rail has been asked to pay out, no callback yet.
 * PROCESSING  - the rail has acknowledged the request (optional intermediate step,
 *               used by slower rails).
 * COMPLETED   - the rail confirmed success. Money has left the building; the
 *               reservation is finalized.
 * FAILED      - the rail confirmed failure (or timed out past its SLA). The
 *               reserved funds are credited back to the merchant.
 */
@Entity
@Table(name = "withdrawals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Withdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private String publicId = UUID.randomUUID().toString();

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency_code", nullable = false, length = 10)
    private String currencyCode;

    /** Bank account reference for fiat, or blockchain address for stablecoins. */
    @Column(nullable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WithdrawalStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum WithdrawalStatus { PENDING, PROCESSING, COMPLETED, FAILED }
}
