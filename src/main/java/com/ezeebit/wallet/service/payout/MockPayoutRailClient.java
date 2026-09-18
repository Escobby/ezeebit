package com.ezeebit.wallet.service.payout;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Simulates a real, asynchronous payout rail: `initiatePayout` returns
 * immediately, and a background task calls back into the callback handler
 * a few seconds later with a success or failure - the same shape a real
 * rail's webhook would produce. In production this class is replaced by an
 * HTTP client and the callback instead arrives at PayoutWebhookController;
 * WithdrawalService's handling logic is identical either way.
 */
@Slf4j
@Component
public class MockPayoutRailClient implements PayoutRailClient {

    private final PayoutCallbackHandler callbackHandler;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /** 0.0-1.0. Exposed for tests/demos that want to force a failing rail. */
    private volatile double simulatedFailureProbability = 0.1;

    public MockPayoutRailClient(@Lazy PayoutCallbackHandler callbackHandler) {
        this.callbackHandler = callbackHandler;
    }

    @Override
    public void initiatePayout(PayoutRequest request) {
        long delayMillis = ThreadLocalRandom.current().nextLong(500, 2500);
        scheduler.schedule(() -> {
            try {
                boolean success = ThreadLocalRandom.current().nextDouble() >= simulatedFailureProbability;
                if (success) {
                    callbackHandler.handlePayoutResult(
                            request.withdrawalPublicId(), true, "rail-" + UUID.randomUUID(), null);
                } else {
                    callbackHandler.handlePayoutResult(
                            request.withdrawalPublicId(), false, null, "Simulated rail rejection (insufficient rail liquidity)");
                }
            } catch (Exception e) {
                log.error("Mock payout rail callback failed for {}", request.withdrawalPublicId(), e);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    public void setSimulatedFailureProbability(double p) {
        this.simulatedFailureProbability = p;
    }
}