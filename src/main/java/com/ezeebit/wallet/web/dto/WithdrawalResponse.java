package com.ezeebit.wallet.web.dto;

import com.ezeebit.wallet.domain.Withdrawal;

import java.time.Instant;

public record WithdrawalResponse(
        String withdrawalId,
        String status,
        String currency,
        long amountMinor,
        String destination,
        String failureReason,
        Instant updatedAt
) {
    public static WithdrawalResponse from(Withdrawal w) {
        return new WithdrawalResponse(w.getPublicId(), w.getStatus().name(), w.getCurrencyCode(),
                w.getAmountMinor(), w.getDestination(), w.getFailureReason(), w.getUpdatedAt());
    }
}
