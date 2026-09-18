package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.Account;
import com.ezeebit.wallet.exception.NotFoundException;
import com.ezeebit.wallet.repository.AccountRepository;
import com.ezeebit.wallet.repository.CurrencyDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AccountProvisioner {

    private final AccountRepository accountRepository;
    private final CurrencyDefinitionRepository currencyDefinitionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureAccountExists(Long merchantId, String currencyCode) {
        if (accountRepository.findByMerchantIdAndCurrencyCode(merchantId, currencyCode).isPresent()) {
            return;
        }
        if (!currencyDefinitionRepository.existsById(currencyCode)) {
            throw new NotFoundException("Unknown currency: " + currencyCode);
        }

        accountRepository.saveAndFlush(Account.builder()
                .merchantId(merchantId)
                .currencyCode(currencyCode)
                .balanceMinor(0L)
                .build());
    }
}