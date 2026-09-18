package com.ezeebit.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record DepositRequest(
        @NotBlank String currency,
        @Positive long amountMinor,
        @NotBlank String idempotencyKey,
        String reference
) {}
