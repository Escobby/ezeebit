package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.*;
import com.ezeebit.wallet.exception.NotFoundException;
import com.ezeebit.wallet.repository.TransactionRepository;
import com.ezeebit.wallet.repository.WithdrawalRepository;
import com.ezeebit.wallet.web.dto.WithdrawalRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * See DepositExecutor for why this is a separate bean from WithdrawalService
 * rather than more methods on it (Spring @Transactional + self-invocation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawalExecutor {

    private final AccountService accountService;
    private final TransactionRepository transactionRepository;
    private final WithdrawalRepository withdrawalRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Withdrawal reserveFunds(MoneyTransaction transaction, Long merchantId, WithdrawalRequest request) {
        MoneyTransaction managedTx = transactionRepository.getReferenceById(transaction.getId());

        Account merchantAccount = accountService.lockExistingAccount(merchantId, request.currency());
        Account systemSuspense = accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, request.currency());

        accountService.debit(merchantAccount, request.amountMinor(), managedTx);
        accountService.credit(systemSuspense, request.amountMinor(), managedTx);

        Withdrawal withdrawal = Withdrawal.builder()
                .merchantId(merchantId)
                .accountId(merchantAccount.getId())
                .transactionId(managedTx.getId())
                .amountMinor(request.amountMinor())
                .currencyCode(request.currency())
                .destination(request.destination())
                .status(Withdrawal.WithdrawalStatus.PENDING)
                .idempotencyKey(request.idempotencyKey())
                .build();
        withdrawalRepository.save(withdrawal);
        // MoneyTransaction stays PENDING - it only becomes COMPLETED/FAILED
        // once the rail actually confirms the payout (see applyPayoutResult).
        return withdrawal;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyPayoutResult(String withdrawalPublicId, boolean success, String externalRef, String failureReason) {
        Withdrawal withdrawal = withdrawalRepository.findByPublicIdForUpdate(withdrawalPublicId)
                .orElseThrow(() -> new NotFoundException("Unknown withdrawal: " + withdrawalPublicId));

        if (withdrawal.getStatus() != Withdrawal.WithdrawalStatus.PENDING
                && withdrawal.getStatus() != Withdrawal.WithdrawalStatus.PROCESSING) {
            // Already terminal - the rail redelivered a notification we've already
            // applied. Do nothing, so a duplicate callback can never double-apply.
            log.info("Ignoring duplicate payout callback for withdrawal {} (already {})",
                    withdrawalPublicId, withdrawal.getStatus());
            return;
        }

        MoneyTransaction originalTx = transactionRepository.getReferenceById(withdrawal.getTransactionId());

        if (success) {
            Account systemSuspense = accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, withdrawal.getCurrencyCode());
            accountService.debit(systemSuspense, withdrawal.getAmountMinor(), originalTx);

            withdrawal.setStatus(Withdrawal.WithdrawalStatus.COMPLETED);
            withdrawal.setExternalRef(externalRef);
            originalTx.setStatus(TransactionStatus.COMPLETED);
        } else {
            reverseReservation(withdrawal, originalTx, failureReason);
        }
    }

    private void reverseReservation(Withdrawal withdrawal, MoneyTransaction originalTx, String failureReason) {
        MoneyTransaction reversal = MoneyTransaction.builder()
                .merchantId(withdrawal.getMerchantId())
                .type(TransactionType.WITHDRAWAL_REVERSAL)
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(withdrawal.getIdempotencyKey() + ":reversal")
                .metadataJson("{\"withdrawalId\":\"" + withdrawal.getPublicId() + "\"}")
                .build();
        transactionRepository.save(reversal);

        Account merchantAccount = accountService.lockOrCreateAccount(withdrawal.getMerchantId(), withdrawal.getCurrencyCode());
        Account systemSuspense = accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, withdrawal.getCurrencyCode());

        accountService.debit(systemSuspense, withdrawal.getAmountMinor(), reversal);
        accountService.credit(merchantAccount, withdrawal.getAmountMinor(), reversal);

        withdrawal.setStatus(Withdrawal.WithdrawalStatus.FAILED);
        withdrawal.setFailureReason(failureReason);
        originalTx.setStatus(TransactionStatus.FAILED);
    }
}
