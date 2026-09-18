package com.ezeebit.wallet.service.payout;

/**
 * Stands in for the payout rail the brief says already exists and is
 * asynchronous: you ask it to pay out, and some time later it tells you
 * whether it succeeded or failed via {@link PayoutCallbackHandler}.
 *
 * initiatePayout must be fire-and-forget from the caller's point of view -
 * WithdrawalService reserves the funds and records a PENDING withdrawal
 * *before* calling this, so a slow or lost rail response never leaves money
 * unaccounted for.
 */
public interface PayoutRailClient {

    void initiatePayout(PayoutRequest request);

    record PayoutRequest(
            String withdrawalPublicId,
            String currencyCode,
            long amountMinor,
            String destination
    ) {}
}
