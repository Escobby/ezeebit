package com.ezeebit.wallet.web;

import com.ezeebit.wallet.domain.Account;
import com.ezeebit.wallet.domain.LedgerEntry;
import com.ezeebit.wallet.exception.NotFoundException;
import com.ezeebit.wallet.service.AccountService;
import com.ezeebit.wallet.web.dto.BalanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/merchants/{merchantId}")
@RequiredArgsConstructor
public class BalanceController {

    private final AccountService accountService;

    @GetMapping("/balances")
    public List<BalanceResponse> balances(@PathVariable Long merchantId) {
        return accountService.getBalances(merchantId).stream().map(BalanceResponse::from).toList();
    }

    /** The audit trail for a single currency's balance: every ledger entry that built it, in order. */
    @GetMapping("/balances/{currency}/statement")
    public List<LedgerEntry> statement(@PathVariable Long merchantId, @PathVariable String currency) {
        Account account = accountService.getBalances(merchantId).stream()
                .filter(a -> a.getCurrencyCode().equalsIgnoreCase(currency))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Merchant " + merchantId + " has no " + currency + " account"));
        return accountService.getStatement(account.getId());
    }
}
