package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.*;
import com.ezeebit.wallet.exception.NotFoundException;
import com.ezeebit.wallet.exception.QuoteAlreadyUsedException;
import com.ezeebit.wallet.exception.QuoteExpiredException;
import com.ezeebit.wallet.repository.ConversionQuoteRepository;
import com.ezeebit.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * See DepositExecutor for why this is a separate bean from ConversionService
 * rather than a second method on it (Spring @Transactional + self-invocation).
 */
@Component
@RequiredArgsConstructor
public class ConversionExecutor {

    private final AccountService accountService;
    private final TransactionRepository transactionRepository;
    private final ConversionQuoteRepository quoteRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(MoneyTransaction transaction, Long merchantId, String quoteId) {
        MoneyTransaction managedTx = transactionRepository.getReferenceById(transaction.getId());

        ConversionQuote quote = quoteRepository.findByPublicIdForUpdate(quoteId)
                .orElseThrow(() -> new NotFoundException("Unknown quote: " + quoteId));

        if (!quote.getMerchantId().equals(merchantId)) {
            throw new NotFoundException("Unknown quote: " + quoteId);
        }
        if (quote.getStatus() == ConversionQuote.QuoteStatus.USED) {
            throw new QuoteAlreadyUsedException("Quote " + quoteId + " has already been used");
        }
        if (quote.getStatus() == ConversionQuote.QuoteStatus.EXPIRED || quote.isExpired()) {
            // Note: this status write happens in a transaction we go on to throw
            // out of below, so it will be rolled back. That's fine - isExpired()
            // (a pure function of expiresAt) is the real source of truth; the
            // persisted status is a best-effort convenience for humans browsing
            // the table, not something anything else in the code depends on.
            quote.setStatus(ConversionQuote.QuoteStatus.EXPIRED);
            throw new QuoteExpiredException("Quote " + quoteId + " has expired; request a new one");
        }

        String fromCcy = quote.getFromCurrency();
        String toCcy = quote.getToCurrency();

        // Lock every account involved in a fixed, currency-alphabetical order
        // (merchant accounts before the system's) so that two concurrent
        // conversions - even in opposite directions between the same two
        // currencies - can never deadlock against each other.
        boolean fromFirst = fromCcy.compareTo(toCcy) <= 0;
        String firstCcy = fromFirst ? fromCcy : toCcy;
        String secondCcy = fromFirst ? toCcy : fromCcy;

        Account merchantFirst = accountService.lockOrCreateAccount(merchantId, firstCcy);
        Account merchantSecond = accountService.lockOrCreateAccount(merchantId, secondCcy);
        Account systemFirst = accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, firstCcy);
        Account systemSecond = accountService.lockOrCreateAccount(Merchant.SYSTEM_MERCHANT_ID, secondCcy);

        Account merchantFrom = fromFirst ? merchantFirst : merchantSecond;
        Account merchantTo = fromFirst ? merchantSecond : merchantFirst;
        Account systemFrom = fromFirst ? systemFirst : systemSecond;
        Account systemTo = fromFirst ? systemSecond : systemFirst;

        // The merchant must never convert more than they actually hold - this
        // is enforced here, under the lock, at execution time (not at quote
        // time, when the balance could since have moved).
        accountService.debit(merchantFrom, quote.getFromAmountMinor(), managedTx);
        accountService.credit(systemFrom, quote.getFromAmountMinor(), managedTx);

        accountService.debit(systemTo, quote.getToAmountMinor(), managedTx);
        accountService.credit(merchantTo, quote.getToAmountMinor(), managedTx);

        quote.setStatus(ConversionQuote.QuoteStatus.USED);
        managedTx.setStatus(TransactionStatus.COMPLETED);
    }
}
