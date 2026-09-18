package com.ezeebit.wallet.service.payout;

/**
 * Implemented by WithdrawalService. This is the seam a real payout rail's
 * webhook would call into (see PayoutWebhookController for the HTTP shape).
 * Must be idempotent: the same withdrawal may be notified more than once.
 */
public interface PayoutCallbackHandler {

    void handlePayoutResult(String withdrawalPublicId, boolean success, String externalRef, String failureReason);
}
