package com.ezeebit.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Shape a real payout rail's webhook would post to PayoutWebhookController. */
public record PayoutCallbackRequest(
        @NotNull Boolean success,
        String externalRef,
        String failureReason
) {}
