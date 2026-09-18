package com.ezeebit.wallet.web.dto;

import com.ezeebit.wallet.domain.MoneyTransaction;

import java.time.Instant;

public record TransactionResponse(
        String transactionId,
        String type,
        String status,
        Instant createdAt
) {
    public static TransactionResponse from(MoneyTransaction tx) {
        return new TransactionResponse(tx.getPublicId(), tx.getType().name(), tx.getStatus().name(), tx.getCreatedAt());
    }
}
