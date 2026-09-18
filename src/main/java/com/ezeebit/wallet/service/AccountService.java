package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.Account;
import com.ezeebit.wallet.domain.LedgerEntry;
import com.ezeebit.wallet.domain.Merchant;
import com.ezeebit.wallet.domain.MoneyTransaction;
import com.ezeebit.wallet.exception.InsufficientBalanceException;
import com.ezeebit.wallet.exception.NotFoundException;
import com.ezeebit.wallet.repository.AccountRepository;
import com.ezeebit.wallet.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountProvisioner accountProvisioner;

    public List<Account> getBalances(Long merchantId) {
        return accountRepository.findByMerchantId(merchantId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Account lockExistingAccount(Long merchantId, String currencyCode) {
        return accountRepository.findForUpdate(merchantId, currencyCode)
                .orElseThrow(() -> new NotFoundException(
                        "Merchant " + merchantId + " has no " + currencyCode + " account"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Account lockOrCreateAccount(Long merchantId, String currencyCode) {
        // 1. Non-locking read check to avoid placing a gap lock prior to REQUIRES_NEW execution
        if (accountRepository.findByMerchantIdAndCurrencyCode(merchantId, currencyCode).isEmpty()) {
            try {
                accountProvisioner.ensureAccountExists(merchantId, currencyCode);
            } catch (Exception ignored) {
                // A racing thread created the account and Tx B rolled back.
                // The row now exists and can be locked safely below.
            }
        }

        // 2. Now lock the row with FOR UPDATE
        return accountRepository.findForUpdate(merchantId, currencyCode)
                .orElseThrow(() -> new NotFoundException("Unknown currency: " + currencyCode));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(Account account, long amountMinor, MoneyTransaction transaction) {
        requirePositive(amountMinor);
        account.setBalanceMinor(account.getBalanceMinor() + amountMinor);
        accountRepository.save(account);
        ledgerEntryRepository.save(LedgerEntry.builder()
                .accountId(account.getId())
                .transactionId(transaction.getId())
                .entryType(LedgerEntry.EntryType.CREDIT)
                .amountMinor(amountMinor)
                .balanceAfterMinor(account.getBalanceMinor())
                .build());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void debit(Account account, long amountMinor, MoneyTransaction transaction) {
        requirePositive(amountMinor);
        boolean isSystemFloatAccount = account.getMerchantId() == Merchant.SYSTEM_MERCHANT_ID;
        if (!isSystemFloatAccount && account.getBalanceMinor() < amountMinor) {
            throw new InsufficientBalanceException(
                    "Merchant " + account.getMerchantId() + " holds " + account.getBalanceMinor()
                            + " minor units of " + account.getCurrencyCode() + " but " + amountMinor + " were requested");
        }
        account.setBalanceMinor(account.getBalanceMinor() - amountMinor);
        accountRepository.save(account);
        ledgerEntryRepository.save(LedgerEntry.builder()
                .accountId(account.getId())
                .transactionId(transaction.getId())
                .entryType(LedgerEntry.EntryType.DEBIT)
                .amountMinor(amountMinor)
                .balanceAfterMinor(account.getBalanceMinor())
                .build());
    }

    public List<LedgerEntry> getStatement(Long accountId) {
        return ledgerEntryRepository.findByAccountIdOrderByIdAsc(accountId);
    }

    private void requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be positive, got " + amountMinor);
        }
    }
}