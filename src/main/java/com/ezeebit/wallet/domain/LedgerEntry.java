package com.ezeebit.wallet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Immutable, append-only double-entry ledger row. Every MoneyTransaction
 * produces a balanced set of these (debits == credits, per currency). This
 * table is never updated or deleted - it is the permanent audit trail that
 * answers "how did this balance reach its current value" for any account,
 * at any point in the past.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private EntryType entryType;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    /** Snapshot of the account balance immediately after this entry was applied. */
    @Column(name = "balance_after_minor", nullable = false)
    private long balanceAfterMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public enum EntryType { DEBIT, CREDIT }
}
