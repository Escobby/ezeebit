package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.TransactionType;
import com.ezeebit.wallet.domain.Withdrawal;
import com.ezeebit.wallet.exception.NotFoundException;
import com.ezeebit.wallet.repository.WithdrawalRepository;
import com.ezeebit.wallet.service.payout.PayoutCallbackHandler;
import com.ezeebit.wallet.service.payout.PayoutRailClient;
import com.ezeebit.wallet.web.dto.WithdrawalRequest;
import com.ezeebit.wallet.web.dto.WithdrawalResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Task 3 - withdrawing funds out of the platform.
 *
 * The payout rail is asynchronous, so this is a two-phase flow:
 *
 *  1. initiateWithdrawal(): under a lock on the merchant's account (see
 *     WithdrawalExecutor.reserveFunds), checks the merchant actually holds
 *     the funds and *immediately reserves them* by debiting the balance and
 *     crediting a SYSTEM "withdrawal suspense" account - all inside one
 *     local transaction, guarded by the same (merchantId, idempotencyKey)
 *     claim used for deposits/conversions. Only after that transaction
 *     commits do we ask the rail to pay out. This ordering means:
 *       - the same withdrawal can never be paid out twice, because the
 *         idempotency claim can only succeed once for a given key;
 *       - a merchant can never withdraw money they don't have, because the
 *         debit is checked and applied under a row lock before the rail is
 *         ever called;
 *       - a slow/lost rail response never leaves money unaccounted for -
 *         the funds are already parked in the suspense account, visible in
 *         the ledger, whether or not the rail ever calls back.
 *
 *  2. handlePayoutResult(): the rail's callback (mocked here, see
 *     MockPayoutRailClient / PayoutWebhookController). Idempotent - a
 *     duplicate notification for an already-terminal withdrawal is a no-op
 *     (see WithdrawalExecutor.applyPayoutResult). On success, the suspense
 *     entry is cleared (money has genuinely left). On failure, the
 *     reservation is reversed via a brand new WITHDRAWAL_REVERSAL
 *     transaction (we never edit history - a reversal is its own auditable
 *     entry) crediting the merchant back in full.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService implements PayoutCallbackHandler {

    private final TransactionService transactionService;
    private final WithdrawalRepository withdrawalRepository;
    private final PayoutRailClient payoutRailClient;
    private final WithdrawalExecutor withdrawalExecutor;

    public WithdrawalResponse initiateWithdrawal(Long merchantId, WithdrawalRequest request) {
        TransactionService.ClaimResult claim = transactionService.claim(
                merchantId, TransactionType.WITHDRAWAL, request.idempotencyKey(),
                "{\"currency\":\"" + request.currency() + "\",\"amountMinor\":" + request.amountMinor()
                        + ",\"destination\":\"" + request.destination() + "\"}");

        if (!claim.isNew()) {
            return withdrawalRepository.findByTransactionId(claim.transaction().getId())
                    .map(WithdrawalResponse::from)
                    // Extremely narrow race: the transaction was claimed a moment ago by another
                    // in-flight request but its Withdrawal row hasn't been inserted yet. Reflect
                    // that the request is already being processed rather than creating a second one.
                    .orElseGet(() -> new WithdrawalResponse(null, claim.transaction().getStatus().name(),
                            request.currency(), request.amountMinor(), request.destination(), null,
                            claim.transaction().getUpdatedAt()));
        }

        Withdrawal withdrawal;
        try {
            withdrawal = withdrawalExecutor.reserveFunds(claim.transaction(), merchantId, request);
        } catch (RuntimeException e) {
            transactionService.markFailed(claim.transaction().getId(), e.getMessage());
            throw e;
        }

        // Only call the (external, unreliable) rail once our reservation has
        // safely committed - never from inside the DB transaction above.
        payoutRailClient.initiatePayout(new PayoutRailClient.PayoutRequest(
                withdrawal.getPublicId(), withdrawal.getCurrencyCode(), withdrawal.getAmountMinor(), withdrawal.getDestination()));

        return WithdrawalResponse.from(withdrawal);
    }

    @Override
    public void handlePayoutResult(String withdrawalPublicId, boolean success, String externalRef, String failureReason) {
        try {
            withdrawalExecutor.applyPayoutResult(withdrawalPublicId, success, externalRef, failureReason);
        } catch (NotFoundException e) {
            log.warn("Payout callback for unknown withdrawal {}: {}", withdrawalPublicId, e.getMessage());
            throw e;
        }
    }
}
