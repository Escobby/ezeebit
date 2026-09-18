package com.ezeebit.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record QuoteRequest(
        @NotBlank String fromCurrency,
        @NotBlank String toCurrency,
        @Positive long fromAmountMinor
) {}
