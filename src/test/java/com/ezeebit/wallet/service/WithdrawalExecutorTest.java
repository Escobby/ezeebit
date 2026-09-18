package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.*;
import com.ezeebit.wallet.exception.NotFoundException;
import com.ezeebit.wallet.repository.TransactionRepository;
import com.ezeebit.wallet.repository.WithdrawalRepository;
import com.ezeebit.wallet.web.dto.WithdrawalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalExecutorTest {

    @Mock AccountService accountService;
    @Mock TransactionRepository transactionRepository;
    @Mock WithdrawalRepository withdrawalRepository;

    WithdrawalExecutor executor;
    static final Long MERCHANT_ID = 1L;

    @BeforeEach
    void setUp() {
        executor = new WithdrawalExecutor(accountService, transactionRepository, withdrawalRepository);
    }

    @Test
    void reserveFunds_debitsMerchantAndCreditsSystemSuspense_thenRecordsPendingWithdrawal() {
        MoneyTransaction managedTx = MoneyTransaction.builder().id(10L).build();
        when(transactionRepository.getReferenceById(10L)).thenReturn(managedTx);
        Account merchantAccount = account(MERCHANT_ID, 1L, "ZAR", 5000L);
        Account systemAccount = account(Merchant.SYSTEM_MERCHANT_ID, 2L, "ZAR", 0L);
        when(accountService.lockExistingAccount(MERCHANT_ID, "ZAR")).thenReturn(merchantAccount);
        when(accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, "ZAR")).thenReturn(systemAccount);

        WithdrawalRequest request = new WithdrawalRequest("ZAR", 2000L, "acc-123", "idem-1");
        Withdrawal withdrawal = executor.reserveFunds(MoneyTransaction.builder().id(10L).build(), MERCHANT_ID, request);

        verify(accountService).debit(merchantAccount, 2000L, managedTx);
        verify(accountService).credit(systemAccount, 2000L, managedTx);
        assertThat(withdrawal.getStatus()).isEqualTo(Withdrawal.WithdrawalStatus.PENDING);
        assertThat(withdrawal.getAmountMinor()).isEqualTo(2000L);
        assertThat(withdrawal.getDestination()).isEqualTo("acc-123");
        verify(withdrawalRepository).save(withdrawal);
        // The MoneyTransaction stays PENDING here - it only resolves once the async rail calls back.
        assertThat(managedTx.getStatus()).isNotEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void reserveFunds_whenMerchantHasNoAccountForCurrency_throwsNotFound_beforeCreatingWithdrawal() {
        when(transactionRepository.getReferenceById(10L)).thenReturn(MoneyTransaction.builder().id(10L).build());
        when(accountService.lockExistingAccount(MERCHANT_ID, "ZAR"))
                .thenThrow(new NotFoundException("no account"));

        assertThatThrownBy(() -> executor.reserveFunds(MoneyTransaction.builder().id(10L).build(), MERCHANT_ID,
                new WithdrawalRequest("ZAR", 2000L, "acc-123", "idem-1")))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(withdrawalRepository);
    }

    @Test
    void applyPayoutResult_onSuccess_clearsSuspenseAndCompletesWithdrawalAndTransaction() {
        Withdrawal withdrawal = pendingWithdrawal();
        MoneyTransaction originalTx = MoneyTransaction.builder().id(withdrawal.getTransactionId()).status(TransactionStatus.PENDING).build();
        when(withdrawalRepository.findByPublicIdForUpdate("wd-1")).thenReturn(Optional.of(withdrawal));
        when(transactionRepository.getReferenceById(withdrawal.getTransactionId())).thenReturn(originalTx);
        Account systemAccount = account(Merchant.SYSTEM_MERCHANT_ID, 2L, "ZAR", 2000L);
        when(accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, "ZAR")).thenReturn(systemAccount);

        executor.applyPayoutResult("wd-1", true, "rail-ref-1", null);

        verify(accountService).debit(systemAccount, 2000L, originalTx);
        assertThat(withdrawal.getStatus()).isEqualTo(Withdrawal.WithdrawalStatus.COMPLETED);
        assertThat(withdrawal.getExternalRef()).isEqualTo("rail-ref-1");
        assertThat(originalTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void applyPayoutResult_onFailure_reversesViaNewTransaction_andCreditsMerchantBackInFull() {
        Withdrawal withdrawal = pendingWithdrawal();
        MoneyTransaction originalTx = MoneyTransaction.builder().id(withdrawal.getTransactionId()).status(TransactionStatus.PENDING).build();
        when(withdrawalRepository.findByPublicIdForUpdate("wd-1")).thenReturn(Optional.of(withdrawal));
        when(transactionRepository.getReferenceById(withdrawal.getTransactionId())).thenReturn(originalTx);
        Account merchantAccount = account(MERCHANT_ID, 1L, "ZAR", 3000L);
        Account systemAccount = account(Merchant.SYSTEM_MERCHANT_ID, 2L, "ZAR", 2000L);
        when(accountService.lockOrCreateAccount(MERCHANT_ID, "ZAR")).thenReturn(merchantAccount);
        when(accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, "ZAR")).thenReturn(systemAccount);

        executor.applyPayoutResult("wd-1", false, null, "rail rejected");

        ArgumentCaptor<MoneyTransaction> reversalCaptor = ArgumentCaptor.forClass(MoneyTransaction.class);
        verify(transactionRepository).save(reversalCaptor.capture());
        MoneyTransaction reversal = reversalCaptor.getValue();
        assertThat(reversal.getType()).isEqualTo(TransactionType.WITHDRAWAL_REVERSAL);
        assertThat(reversal.getIdempotencyKey()).isEqualTo(withdrawal.getIdempotencyKey() + ":reversal");

        verify(accountService).debit(systemAccount, 2000L, reversal);
        verify(accountService).credit(merchantAccount, 2000L, reversal);
        assertThat(withdrawal.getStatus()).isEqualTo(Withdrawal.WithdrawalStatus.FAILED);
        assertThat(withdrawal.getFailureReason()).isEqualTo("rail rejected");
        assertThat(originalTx.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void applyPayoutResult_duplicateCallbackOnAlreadyTerminalWithdrawal_isANoOp() {
        // The rail (or a real webhook) redelivers a notification for a
        // withdrawal that was already completed by an earlier callback.
        Withdrawal withdrawal = pendingWithdrawal();
        withdrawal.setStatus(Withdrawal.WithdrawalStatus.COMPLETED);
        when(withdrawalRepository.findByPublicIdForUpdate("wd-1")).thenReturn(Optional.of(withdrawal));

        executor.applyPayoutResult("wd-1", true, "rail-ref-2", null);

        verifyNoInteractions(accountService);
        verify(transactionRepository, never()).save(any());
        assertThat(withdrawal.getExternalRef()).isNull(); // untouched by the duplicate callback
    }

    @Test
    void applyPayoutResult_unknownWithdrawal_throwsNotFound() {
        when(withdrawalRepository.findByPublicIdForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.applyPayoutResult("missing", true, "ref", null))
                .isInstanceOf(NotFoundException.class);
    }

    private Withdrawal pendingWithdrawal() {
        Withdrawal w = Withdrawal.builder()
                .merchantId(MERCHANT_ID).accountId(1L).transactionId(10L)
                .amountMinor(2000L).currencyCode("ZAR").destination("acc-123")
                .status(Withdrawal.WithdrawalStatus.PENDING).idempotencyKey("idem-1")
                .build();
        w.setPublicId("wd-1");
        return w;
    }

    private Account account(long merchantId, long id, String currency, long balance) {
        Account a = Account.builder().merchantId(merchantId).currencyCode(currency).balanceMinor(balance).build();
        a.setId(id);
        return a;
    }
}
