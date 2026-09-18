package com.ezeebit.wallet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A merchant's balance in a single currency. Never mixes currencies -
 * a merchant with ZAR and USDT has two Account rows, each independently
 * accounted for. `balanceMinor` is a cached, authoritative snapshot that is
 * always mutated in the same DB transaction as the LedgerEntry rows that
 * justify the change, under a row lock (see AccountService).
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "currency_code", nullable = false, length = 10)
    private String currencyCode;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
}
