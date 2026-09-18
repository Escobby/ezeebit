package com.ezeebit.wallet.web;

import com.ezeebit.wallet.service.WithdrawalService;
import com.ezeebit.wallet.web.dto.PayoutCallbackRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Where a real payout rail's webhook would land. In this exercise,
 * MockPayoutRailClient calls WithdrawalService directly to simulate the
 * same event, but this endpoint exists so the flow can be triggered by hand
 * (or by a test) exactly the way production would receive it - and so the
 * idempotency behaviour (a redelivered notification is a no-op) is directly
 * observable over HTTP.
 */
@RestController
@RequestMapping("/webhooks/payouts/{withdrawalId}")
@RequiredArgsConstructor
public class PayoutWebhookController {

    private final WithdrawalService withdrawalService;

    @PostMapping
    public ResponseEntity<Void> handleCallback(@PathVariable String withdrawalId,
                                                @Valid @RequestBody PayoutCallbackRequest request) {
        withdrawalService.handlePayoutResult(withdrawalId, request.success(), request.externalRef(), request.failureReason());
        return ResponseEntity.noContent().build();
    }
}
