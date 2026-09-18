package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.Account;
import com.ezeebit.wallet.domain.LedgerEntry;
import com.ezeebit.wallet.domain.Merchant;
import com.ezeebit.wallet.domain.MoneyTransaction;
import com.ezeebit.wallet.exception.InsufficientBalanceException;
import com.ezeebit.wallet.exception.NotFoundException;
import com.ezeebit.wallet.repository.AccountRepository;
import com.ezeebit.wallet.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock AccountProvisioner accountProvisioner;

    AccountService accountService;
    MoneyTransaction tx;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, ledgerEntryRepository, accountProvisioner);
        tx = MoneyTransaction.builder().id(99L).build();
    }

    @Test
    void credit_increasesBalanceAndWritesLedgerEntry() {
        Account account = accountOf(1L, 5L, "ZAR", 1_000L);

        accountService.credit(account, 500L, tx);

        assertThat(account.getBalanceMinor()).isEqualTo(1_500L);
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());
        LedgerEntry entry = captor.getValue();
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntry.EntryType.CREDIT);
        assertThat(entry.getAmountMinor()).isEqualTo(500L);
        assertThat(entry.getBalanceAfterMinor()).isEqualTo(1_500L);
        assertThat(entry.getAccountId()).isEqualTo(5L);
        assertThat(entry.getTransactionId()).isEqualTo(99L);
    }

    @Test
    void debit_withSufficientBalance_decreasesBalanceAndWritesLedgerEntry() {
        Account account = accountOf(1L, 5L, "ZAR", 1_000L);

        accountService.debit(account, 400L, tx);

        assertThat(account.getBalanceMinor()).isEqualTo(600L);
        verify(ledgerEntryRepository).save(argThat(e -> e.getEntryType() == LedgerEntry.EntryType.DEBIT
                && e.getAmountMinor() == 400L && e.getBalanceAfterMinor() == 600L));
    }

    @Test
    void debit_withInsufficientBalance_throwsAndLeavesBalanceUntouched() {
        Account account = accountOf(1L, 5L, "ZAR", 100L);

        assertThatThrownBy(() -> accountService.debit(account, 500L, tx))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(account.getBalanceMinor()).isEqualTo(100L);
        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void debit_onSystemFloatAccount_isAllowedToGoNegative() {
        Account systemAccount = accountOf(Merchant.SYSTEM_MERCHANT_ID, 9L, "USDT", 0L);

        accountService.debit(systemAccount, 250L, tx);

        assertThat(systemAccount.getBalanceMinor()).isEqualTo(-250L);
    }

    @Test
    void debit_rejectsNonPositiveAmounts() {
        Account account = accountOf(1L, 5L, "ZAR", 1_000L);
        assertThatThrownBy(() -> accountService.debit(account, 0L, tx)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accountService.debit(account, -10L, tx)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lockOrCreateAccount_whenAccountExists_returnsItWithoutProvisioning() {
        Account existing = accountOf(1L, 5L, "ZAR", 0L);
        when(accountRepository.findByMerchantIdAndCurrencyCode(1L, "ZAR")).thenReturn(Optional.of(existing));
        when(accountRepository.findForUpdate(1L, "ZAR")).thenReturn(Optional.of(existing));

        Account result = accountService.lockOrCreateAccount(1L, "ZAR");

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(accountProvisioner);
    }

    @Test
    void lockOrCreateAccount_whenMissing_provisionsThenRelocksAndReturnsIt() {
        Account created = accountOf(1L, 5L, "ZAR", 0L);
        when(accountRepository.findByMerchantIdAndCurrencyCode(1L, "ZAR")).thenReturn(Optional.empty());
        when(accountRepository.findForUpdate(1L, "ZAR")).thenReturn(Optional.of(created));

        Account result = accountService.lockOrCreateAccount(1L, "ZAR");

        assertThat(result).isSameAs(created);
        verify(accountProvisioner).ensureAccountExists(1L, "ZAR");
    }

    @Test
    void lockExistingAccount_whenMissing_throwsNotFound() {
        when(accountRepository.findForUpdate(1L, "ZAR")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.lockExistingAccount(1L, "ZAR"))
                .isInstanceOf(NotFoundException.class);
    }

    private Account accountOf(long merchantId, long accountId, String currency, long balance) {
        Account a = Account.builder().merchantId(merchantId).currencyCode(currency).balanceMinor(balance).build();
        a.setId(accountId);
        return a;
    }
}