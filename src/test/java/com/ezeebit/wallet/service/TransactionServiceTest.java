package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.MoneyTransaction;
import com.ezeebit.wallet.domain.TransactionStatus;
import com.ezeebit.wallet.domain.TransactionType;
import com.ezeebit.wallet.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock TransactionRepository transactionRepository;
    TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository);
    }

    @Test
    void claim_withFreshKey_insertsAndReturnsNewClaim() {
        when(transactionRepository.saveAndFlush(any(MoneyTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionService.ClaimResult result = transactionService.claim(
                1L, TransactionType.DEPOSIT, "idem-1", "{}");

        assertThat(result.isNew()).isTrue();
        assertThat(result.transaction().getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(result.transaction().getIdempotencyKey()).isEqualTo("idem-1");
        verify(transactionRepository).saveAndFlush(any(MoneyTransaction.class));
    }

    @Test
    void claim_replayedKey_doesNotInsertTwice_returnsOriginalTransaction() {
        MoneyTransaction original = MoneyTransaction.builder()
                .id(42L).merchantId(1L).type(TransactionType.DEPOSIT)
                .status(TransactionStatus.COMPLETED).idempotencyKey("idem-1").build();
        when(transactionRepository.findByMerchantIdAndIdempotencyKey(1L, "idem-1"))
                .thenReturn(Optional.of(original));

        TransactionService.ClaimResult result = transactionService.claim(1L, TransactionType.DEPOSIT, "idem-1", "{}");

        assertThat(result.isNew()).isFalse();
        assertThat(result.transaction()).isSameAs(original);
    }

    @Test
    void markCompleted_setsStatusToCompleted() {
        MoneyTransaction tx = MoneyTransaction.builder().id(1L).status(TransactionStatus.PENDING).build();
        when(transactionRepository.getReferenceById(1L)).thenReturn(tx);

        transactionService.markCompleted(1L);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void markFailed_setsStatusToFailedAndRecordsReason() {
        MoneyTransaction tx = MoneyTransaction.builder().id(1L).status(TransactionStatus.PENDING).build();
        when(transactionRepository.getReferenceById(1L)).thenReturn(tx);

        transactionService.markFailed(1L, "insufficient balance");

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getMetadataJson()).contains("insufficient balance");
    }
}