package com.ezeebit.wallet.web.dto;

import com.ezeebit.wallet.domain.Account;

import java.time.Instant;

public record BalanceResponse(
        Long merchantId,
        String currency,
        long balanceMinor,
        Instant updatedAt
) {
    public static BalanceResponse from(Account account) {
        return new BalanceResponse(account.getMerchantId(), account.getCurrencyCode(),
                account.getBalanceMinor(), account.getUpdatedAt());
    }
}
