package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.TransactionType;
import com.ezeebit.wallet.repository.TransactionRepository;
import com.ezeebit.wallet.web.dto.DepositRequest;
import com.ezeebit.wallet.web.dto.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Task 1 - holding a merchant's money across currencies. Money arrives (a
 * deposit, e.g. an on-chain confirmation or a bank credit already validated
 * upstream) and the merchant's balance in that currency goes up.
 *
 * Correctness properties:
 *  - Idempotent: the same idempotencyKey applied twice never double-credits
 *    (see TransactionService.claim).
 *  - Currency-safe: money always lands in an Account scoped to exactly one
 *    currency; there is no code path that adds an amount to the "wrong"
 *    currency's balance.
 *  - Auditable: every credit is backed by an immutable LedgerEntry.
 */
@Service
@RequiredArgsConstructor
public class DepositService {

    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final DepositExecutor depositExecutor;

    public TransactionResponse deposit(Long merchantId, DepositRequest request) {
        TransactionService.ClaimResult claim = transactionService.claim(
                merchantId, TransactionType.DEPOSIT, request.idempotencyKey(),
                "{\"currency\":\"" + request.currency() + "\",\"amountMinor\":" + request.amountMinor() + "}");

        if (!claim.isNew()) {
            // Duplicate request (retry over a flaky connection): hand back the
            // original outcome instead of crediting the account again.
            return TransactionResponse.from(claim.transaction());
        }

        try {
            depositExecutor.execute(claim.transaction(), merchantId, request);
        } catch (RuntimeException e) {
            transactionService.markFailed(claim.transaction().getId(), e.getMessage());
            throw e;
        }

        return TransactionResponse.from(transactionRepository.findById(claim.transaction().getId()).orElseThrow());
    }
}
