package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.*;
import com.ezeebit.wallet.exception.InsufficientBalanceException;
import com.ezeebit.wallet.exception.NotFoundException;
import com.ezeebit.wallet.exception.QuoteAlreadyUsedException;
import com.ezeebit.wallet.exception.QuoteExpiredException;
import com.ezeebit.wallet.repository.ConversionQuoteRepository;
import com.ezeebit.wallet.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversionExecutorTest {

    @Mock AccountService accountService;
    @Mock TransactionRepository transactionRepository;
    @Mock ConversionQuoteRepository quoteRepository;

    ConversionExecutor executor;
    MoneyTransaction managedTx;

    static final Long MERCHANT_ID = 1L;

    @BeforeEach
    void setUp() {
        executor = new ConversionExecutor(accountService, transactionRepository, quoteRepository);
        managedTx = MoneyTransaction.builder().id(10L).status(TransactionStatus.PENDING).build();
        when(transactionRepository.getReferenceById(10L)).thenReturn(managedTx);
    }

    @Test
    void execute_happyPath_movesBothLegsAndMarksQuoteUsedAndTxCompleted() {
        ConversionQuote quote = activeQuote("USDT", "ZAR", 100L, 1850L);
        when(quoteRepository.findByPublicIdForUpdate("q1")).thenReturn(Optional.of(quote));

        Account merchantUsdt = account(MERCHANT_ID, 1L, "USDT", 1000L);
        Account merchantZar = account(MERCHANT_ID, 2L, "ZAR", 0L);
        Account systemUsdt = account(Merchant.SYSTEM_MERCHANT_ID, 3L, "USDT", 0L);
        Account systemZar = account(Merchant.SYSTEM_MERCHANT_ID, 4L, "ZAR", 50000L);
        when(accountService.lockOrCreateAccount(MERCHANT_ID, "USDT")).thenReturn(merchantUsdt);
        when(accountService.lockOrCreateAccount(MERCHANT_ID, "ZAR")).thenReturn(merchantZar);
        when(accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, "USDT")).thenReturn(systemUsdt);
        when(accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, "ZAR")).thenReturn(systemZar);

        MoneyTransaction inputTx = MoneyTransaction.builder().id(10L).build();
        executor.execute(inputTx, MERCHANT_ID, "q1");

        verify(accountService).debit(merchantUsdt, 100L, managedTx);
        verify(accountService).credit(systemUsdt, 100L, managedTx);
        verify(accountService).debit(systemZar, 1850L, managedTx);
        verify(accountService).credit(merchantZar, 1850L, managedTx);
        assertThat(quote.getStatus()).isEqualTo(ConversionQuote.QuoteStatus.USED);
        assertThat(managedTx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void execute_locksAccountsInAlphabeticalCurrencyOrder_toAvoidDeadlocks() {
        ConversionQuote quote = activeQuote("USDT", "ZAR", 100L, 1850L);
        when(quoteRepository.findByPublicIdForUpdate("q1")).thenReturn(Optional.of(quote));
        stubAllAccounts();

        executor.execute(MoneyTransaction.builder().id(10L).build(), MERCHANT_ID, "q1");

        InOrder inOrder = inOrder(accountService);
        inOrder.verify(accountService).lockOrCreateAccount(MERCHANT_ID, "USDT");   // alphabetically first ('U' < 'Z')
        inOrder.verify(accountService).lockOrCreateAccount(MERCHANT_ID, "ZAR");
        inOrder.verify(accountService).lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, "USDT");
        inOrder.verify(accountService).lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, "ZAR");
    }

    @Test
    void execute_expiredQuote_throwsAndNeverTouchesAnyAccount() {
        ConversionQuote quote = ConversionQuote.builder()
                .publicId("q1").merchantId(MERCHANT_ID).fromCurrency("USDT").toCurrency("ZAR")
                .fromAmountMinor(100L).toAmountMinor(1850L).rate(BigDecimal.TEN)
                .status(ConversionQuote.QuoteStatus.ACTIVE).expiresAt(Instant.now().minusSeconds(5))
                .build();
        when(quoteRepository.findByPublicIdForUpdate("q1")).thenReturn(Optional.of(quote));

        assertThatThrownBy(() -> executor.execute(MoneyTransaction.builder().id(10L).build(), MERCHANT_ID, "q1"))
                .isInstanceOf(QuoteExpiredException.class);

        verifyNoInteractions(accountService);
    }

    @Test
    void execute_alreadyUsedQuote_throwsAndNeverTouchesAnyAccount() {
        ConversionQuote quote = activeQuote("USDT", "ZAR", 100L, 1850L);
        quote.setStatus(ConversionQuote.QuoteStatus.USED);
        when(quoteRepository.findByPublicIdForUpdate("q1")).thenReturn(Optional.of(quote));

        assertThatThrownBy(() -> executor.execute(MoneyTransaction.builder().id(10L).build(), MERCHANT_ID, "q1"))
                .isInstanceOf(QuoteAlreadyUsedException.class);

        verifyNoInteractions(accountService);
    }

    @Test
    void execute_quoteBelongsToAnotherMerchant_throwsNotFound_ratherThanLeakingItsExistence() {
        ConversionQuote quote = activeQuote("USDT", "ZAR", 100L, 1850L);
        quote.setMerchantId(999L);
        when(quoteRepository.findByPublicIdForUpdate("q1")).thenReturn(Optional.of(quote));

        assertThatThrownBy(() -> executor.execute(MoneyTransaction.builder().id(10L).build(), MERCHANT_ID, "q1"))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(accountService);
    }

    @Test
    void execute_merchantLacksFunds_debitThrows_soTheCreditLegNeverHappens() {
        ConversionQuote quote = activeQuote("USDT", "ZAR", 100L, 1850L);
        when(quoteRepository.findByPublicIdForUpdate("q1")).thenReturn(Optional.of(quote));

        Account merchantUsdt = account(MERCHANT_ID, 1L, "USDT", 10L); // not enough
        Account merchantZar = account(MERCHANT_ID, 2L, "ZAR", 0L);
        Account systemUsdt = account(Merchant.SYSTEM_MERCHANT_ID, 3L, "USDT", 0L);
        Account systemZar = account(Merchant.SYSTEM_MERCHANT_ID, 4L, "ZAR", 50000L);
        when(accountService.lockOrCreateAccount(MERCHANT_ID, "ZAR")).thenReturn(merchantZar);
        when(accountService.lockOrCreateAccount(MERCHANT_ID, "USDT")).thenReturn(merchantUsdt);
        when(accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, "ZAR")).thenReturn(systemZar);
        when(accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, "USDT")).thenReturn(systemUsdt);
        doThrow(new InsufficientBalanceException("not enough"))
                .when(accountService).debit(merchantUsdt, 100L, managedTx);

        assertThatThrownBy(() -> executor.execute(MoneyTransaction.builder().id(10L).build(), MERCHANT_ID, "q1"))
                .isInstanceOf(InsufficientBalanceException.class);

        // The platform never credits itself the merchant's funds unless the debit actually succeeded.
        verify(accountService, never()).credit(eq(systemUsdt), any(Long.class), any());
        verify(accountService, never()).debit(eq(systemZar), any(Long.class), any());
        verify(accountService, never()).credit(eq(merchantZar), any(Long.class), any());
        assertThat(quote.getStatus()).isEqualTo(ConversionQuote.QuoteStatus.ACTIVE);
    }

    private void stubAllAccounts() {
        when(accountService.lockOrCreateAccount(eq(MERCHANT_ID), any())).thenAnswer(inv ->
                account(MERCHANT_ID, 1L, inv.getArgument(1), 100_000L));
        when(accountService.lockOrCreateAccount(eq(Merchant.SYSTEM_MERCHANT_ID), any())).thenAnswer(inv ->
                account(Merchant.SYSTEM_MERCHANT_ID, 2L, inv.getArgument(1), 100_000L));
    }

    private ConversionQuote activeQuote(String from, String to, long fromAmount, long toAmount) {
        return ConversionQuote.builder()
                .publicId("q1").merchantId(MERCHANT_ID).fromCurrency(from).toCurrency(to)
                .fromAmountMinor(fromAmount).toAmountMinor(toAmount).rate(BigDecimal.TEN)
                .status(ConversionQuote.QuoteStatus.ACTIVE).expiresAt(Instant.now().plusSeconds(30))
                .build();
    }

    private Account account(long merchantId, long id, String currency, long balance) {
        Account a = Account.builder().merchantId(merchantId).currencyCode(currency).balanceMinor(balance).build();
        a.setId(id);
        return a;
    }
}
