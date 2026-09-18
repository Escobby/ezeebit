package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.Account;
import com.ezeebit.wallet.domain.MoneyTransaction;
import com.ezeebit.wallet.domain.TransactionStatus;
import com.ezeebit.wallet.repository.TransactionRepository;
import com.ezeebit.wallet.web.dto.DepositRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Split out from DepositService as its own Spring bean deliberately: Spring's
 * @Transactional is proxy-based, and a proxy is only consulted on a call that
 * comes from *another* bean. If this method lived on DepositService and
 * DepositService called it on itself (`this.execute(...)`), the REQUIRES_NEW
 * annotation would silently be ignored. Routing the call through a separate
 * bean guarantees the proxy - and therefore the transaction boundary - is
 * actually applied.
 */
@Component
@RequiredArgsConstructor
public class DepositExecutor {

    private final AccountService accountService;
    private final TransactionRepository transactionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(MoneyTransaction transaction, Long merchantId, DepositRequest request) {
        // `transaction` was loaded in TransactionService.claim()'s own transaction
        // and is now detached; re-attach it here so the status change below
        // is actually flushed as part of this transaction.
        MoneyTransaction managed = transactionRepository.getReferenceById(transaction.getId());
        Account account = accountService.lockOrCreateAccount(merchantId, request.currency());
        accountService.credit(account, request.amountMinor(), managed);
        managed.setStatus(TransactionStatus.COMPLETED);
    }
}
