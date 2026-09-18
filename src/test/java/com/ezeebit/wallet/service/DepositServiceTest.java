package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.MoneyTransaction;
import com.ezeebit.wallet.domain.TransactionStatus;
import com.ezeebit.wallet.domain.TransactionType;
import com.ezeebit.wallet.exception.InsufficientBalanceException;
import com.ezeebit.wallet.repository.TransactionRepository;
import com.ezeebit.wallet.web.dto.DepositRequest;
import com.ezeebit.wallet.web.dto.TransactionResponse;
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
class DepositServiceTest {

    @Mock TransactionService transactionService;
    @Mock TransactionRepository transactionRepository;
    @Mock DepositExecutor depositExecutor;

    DepositService depositService;

    @BeforeEach
    void setUp() {
        depositService = new DepositService(transactionService, transactionRepository, depositExecutor);
    }

    @Test
    void deposit_happyPath_executesOnceAndReturnsCompletedTransaction() {
        MoneyTransaction pending = MoneyTransaction.builder()
                .id(1L).merchantId(7L).type(TransactionType.DEPOSIT).status(TransactionStatus.PENDING)
                .idempotencyKey("k1").build();
        MoneyTransaction completed = MoneyTransaction.builder()
                .id(1L).merchantId(7L).type(TransactionType.DEPOSIT).status(TransactionStatus.COMPLETED)
                .idempotencyKey("k1").build();
        when(transactionService.claim(eq(7L), eq(TransactionType.DEPOSIT), eq("k1"), any()))
                .thenReturn(new TransactionService.ClaimResult(pending, true));
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(completed));

        TransactionResponse response = depositService.deposit(7L,
                new DepositRequest("ZAR", 1000L, "k1", null));

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(depositExecutor).execute(eq(pending), eq(7L), any(DepositRequest.class));
    }

    @Test
    void deposit_replayedIdempotencyKey_neverCallsExecutorAgain() {
        // Simulates the dashboard firing the same deposit twice over a flaky connection.
        MoneyTransaction alreadyCompleted = MoneyTransaction.builder()
                .id(1L).merchantId(7L).type(TransactionType.DEPOSIT).status(TransactionStatus.COMPLETED)
                .idempotencyKey("k1").build();
        when(transactionService.claim(eq(7L), eq(TransactionType.DEPOSIT), eq("k1"), any()))
                .thenReturn(new TransactionService.ClaimResult(alreadyCompleted, false));

        TransactionResponse response = depositService.deposit(7L,
                new DepositRequest("ZAR", 1000L, "k1", null));

        assertThat(response.status()).isEqualTo("COMPLETED");
        verifyNoInteractions(depositExecutor);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void deposit_whenExecutionFails_marksTransactionFailedAndRethrows() {
        MoneyTransaction pending = MoneyTransaction.builder()
                .id(1L).merchantId(7L).type(TransactionType.DEPOSIT).status(TransactionStatus.PENDING)
                .idempotencyKey("k1").build();
        when(transactionService.claim(eq(7L), eq(TransactionType.DEPOSIT), eq("k1"), any()))
                .thenReturn(new TransactionService.ClaimResult(pending, true));
        doThrow(new InsufficientBalanceException("boom"))
                .when(depositExecutor).execute(any(), any(), any());

        assertThatThrownBy(() -> depositService.deposit(7L, new DepositRequest("ZAR", 1000L, "k1", null)))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(transactionService).markFailed(1L, "boom");
    }
}
