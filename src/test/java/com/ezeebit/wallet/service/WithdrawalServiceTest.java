package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.MoneyTransaction;
import com.ezeebit.wallet.domain.TransactionStatus;
import com.ezeebit.wallet.domain.TransactionType;
import com.ezeebit.wallet.domain.Withdrawal;
import com.ezeebit.wallet.exception.InsufficientBalanceException;
import com.ezeebit.wallet.repository.WithdrawalRepository;
import com.ezeebit.wallet.service.payout.PayoutRailClient;
import com.ezeebit.wallet.web.dto.WithdrawalRequest;
import com.ezeebit.wallet.web.dto.WithdrawalResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock TransactionService transactionService;
    @Mock WithdrawalRepository withdrawalRepository;
    @Mock PayoutRailClient payoutRailClient;
    @Mock WithdrawalExecutor withdrawalExecutor;

    WithdrawalService withdrawalService;
    static final Long MERCHANT_ID = 1L;

    @BeforeEach
    void setUp() {
        withdrawalService = new WithdrawalService(transactionService, withdrawalRepository, payoutRailClient, withdrawalExecutor);
    }

    @Test
    void initiateWithdrawal_happyPath_reservesFundsThenCallsRailAfterCommit() {
        MoneyTransaction pending = MoneyTransaction.builder().id(1L).merchantId(MERCHANT_ID)
                .type(TransactionType.WITHDRAWAL).status(TransactionStatus.PENDING).idempotencyKey("k1").build();
        when(transactionService.claim(eq(MERCHANT_ID), eq(TransactionType.WITHDRAWAL), eq("k1"), any()))
                .thenReturn(new TransactionService.ClaimResult(pending, true));
        Withdrawal saved = Withdrawal.builder().merchantId(MERCHANT_ID).accountId(1L).transactionId(1L)
                .amountMinor(500L).currencyCode("ZAR").destination("bank-acc")
                .status(Withdrawal.WithdrawalStatus.PENDING).idempotencyKey("k1").build();
        saved.setPublicId("wd-1");
        WithdrawalRequest request = new WithdrawalRequest("ZAR", 500L, "bank-acc", "k1");
        when(withdrawalExecutor.reserveFunds(pending, MERCHANT_ID, request)).thenReturn(saved);

        WithdrawalResponse response = withdrawalService.initiateWithdrawal(MERCHANT_ID, request);

        assertThat(response.withdrawalId()).isEqualTo("wd-1");
        assertThat(response.status()).isEqualTo("PENDING");
        verify(payoutRailClient).initiatePayout(new PayoutRailClient.PayoutRequest("wd-1", "ZAR", 500L, "bank-acc"));
    }

    @Test
    void initiateWithdrawal_replayedIdempotencyKey_neverReservesFundsTwiceOrCallsRailAgain() {
        // The dashboard fired the same withdrawal request twice over a flaky connection.
        MoneyTransaction alreadyPending = MoneyTransaction.builder().id(1L).merchantId(MERCHANT_ID)
                .type(TransactionType.WITHDRAWAL).status(TransactionStatus.PENDING).idempotencyKey("k1").build();
        when(transactionService.claim(eq(MERCHANT_ID), eq(TransactionType.WITHDRAWAL), eq("k1"), any()))
                .thenReturn(new TransactionService.ClaimResult(alreadyPending, false));
        Withdrawal existing = Withdrawal.builder().merchantId(MERCHANT_ID).accountId(1L).transactionId(1L)
                .amountMinor(500L).currencyCode("ZAR").destination("bank-acc")
                .status(Withdrawal.WithdrawalStatus.PENDING).idempotencyKey("k1").build();
        existing.setPublicId("wd-1");
        when(withdrawalRepository.findByTransactionId(1L)).thenReturn(Optional.of(existing));

        WithdrawalResponse response = withdrawalService.initiateWithdrawal(MERCHANT_ID,
                new WithdrawalRequest("ZAR", 500L, "bank-acc", "k1"));

        assertThat(response.withdrawalId()).isEqualTo("wd-1");
        verifyNoInteractions(withdrawalExecutor);
        verifyNoInteractions(payoutRailClient);
    }

    @Test
    void initiateWithdrawal_whenReservationFails_marksFailedAndNeverCallsRail() {
        MoneyTransaction pending = MoneyTransaction.builder().id(1L).merchantId(MERCHANT_ID)
                .type(TransactionType.WITHDRAWAL).status(TransactionStatus.PENDING).idempotencyKey("k1").build();
        when(transactionService.claim(eq(MERCHANT_ID), eq(TransactionType.WITHDRAWAL), eq("k1"), any()))
                .thenReturn(new TransactionService.ClaimResult(pending, true));
        when(withdrawalExecutor.reserveFunds(eq(pending), eq(MERCHANT_ID), any()))
                .thenThrow(new InsufficientBalanceException("not enough funds"));

        assertThatThrownBy(() -> withdrawalService.initiateWithdrawal(MERCHANT_ID,
                new WithdrawalRequest("ZAR", 500L, "bank-acc", "k1")))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(transactionService).markFailed(1L, "not enough funds");
        verifyNoInteractions(payoutRailClient);
    }

    @Test
    void handlePayoutResult_delegatesToExecutor() {
        withdrawalService.handlePayoutResult("wd-1", true, "ref-1", null);

        verify(withdrawalExecutor).applyPayoutResult("wd-1", true, "ref-1", null);
    }
}
