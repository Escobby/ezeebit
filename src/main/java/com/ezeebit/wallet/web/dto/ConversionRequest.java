package com.ezeebit.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ConversionRequest(
        @NotBlank String quoteId,
        @NotBlank String idempotencyKey
) {}
