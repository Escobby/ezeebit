package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.CurrencyDefinition;
import com.ezeebit.wallet.domain.ConversionQuote;
import com.ezeebit.wallet.domain.TransactionType;
import com.ezeebit.wallet.exception.InvalidRequestException;
import com.ezeebit.wallet.exception.NotFoundException;
import com.ezeebit.wallet.repository.ConversionQuoteRepository;
import com.ezeebit.wallet.repository.CurrencyDefinitionRepository;
import com.ezeebit.wallet.repository.TransactionRepository;
import com.ezeebit.wallet.service.exchangerate.ExchangeRateClient;
import com.ezeebit.wallet.web.dto.ConversionRequest;
import com.ezeebit.wallet.web.dto.QuoteRequest;
import com.ezeebit.wallet.web.dto.QuoteResponse;
import com.ezeebit.wallet.web.dto.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Task 2 - converting one currency a merchant holds into another.
 *
 * Design: quote-then-execute, so the merchant always converts at a price
 * they saw and agreed to, never at "whatever the market is doing right
 * now":
 *
 *   1. createQuote() calls the (slow, moving) exchange-rate feed once,
 *      locks that rate into a short-lived ConversionQuote row, and hands
 *      the merchant a quote id. No money moves yet, so no balance check
 *      is needed here.
 *   2. executeConversion() (via ConversionExecutor) spends a quote id. It
 *      re-validates the quote (not expired, not already used, belongs to
 *      this merchant), takes a lock on the merchant's balances, checks the
 *      merchant actually holds the "from" amount *at execution time*, and
 *      moves the money in one local transaction.
 *
 * A short quote TTL (ezeebit.conversion.quote-ttl-seconds) is what stops
 * the platform ending up on the wrong side of a moving market: Ezeebit's
 * price exposure on any one quote is bounded to that window, after which
 * the merchant must ask for a fresh price.
 *
 * The conversion itself is posted as two independent, currency-scoped
 * double-entry postings against a SYSTEM clearing account (see
 * Merchant.SYSTEM_MERCHANT_ID and V2__seed_reference_data.sql):
 *   debit  merchant/FROM_CCY  ->  credit SYSTEM/FROM_CCY
 *   debit  SYSTEM/TO_CCY      ->  credit merchant/TO_CCY
 * This keeps every single-currency ledger balanced (never mixes two
 * currencies inside one entry) while still fully explaining where the
 * money went.
 */
@Service
@RequiredArgsConstructor
public class ConversionService {

    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final ConversionQuoteRepository quoteRepository;
    private final CurrencyDefinitionRepository currencyDefinitionRepository;
    private final ExchangeRateClient exchangeRateClient;
    private final ConversionExecutor conversionExecutor;

    @Value("${ezeebit.conversion.quote-ttl-seconds:20}")
    private long quoteTtlSeconds;

    // ---- Step 1: quote ----------------------------------------------------

    public QuoteResponse createQuote(Long merchantId, QuoteRequest request) {
        if (request.fromCurrency().equalsIgnoreCase(request.toCurrency())) {
            throw new InvalidRequestException("fromCurrency and toCurrency must differ");
        }
        CurrencyDefinition from = currencyDefinitionRepository.findById(request.fromCurrency())
                .orElseThrow(() -> new NotFoundException("Unknown currency: " + request.fromCurrency()));
        CurrencyDefinition to = currencyDefinitionRepository.findById(request.toCurrency())
                .orElseThrow(() -> new NotFoundException("Unknown currency: " + request.toCurrency()));

        BigDecimal rate = exchangeRateClient.getRate(from.getCode(), to.getCode());

        BigDecimal fromAmountMajor = minorToMajor(request.fromAmountMinor(), from.getDecimals());
        BigDecimal toAmountMajor = fromAmountMajor.multiply(rate);
        long toAmountMinor = majorToMinor(toAmountMajor, to.getDecimals());

        if (toAmountMinor <= 0) {
            throw new InvalidRequestException("Conversion amount too small to produce a non-zero result");
        }

        ConversionQuote quote = ConversionQuote.builder()
                .merchantId(merchantId)
                .fromCurrency(from.getCode())
                .toCurrency(to.getCode())
                .fromAmountMinor(request.fromAmountMinor())
                .toAmountMinor(toAmountMinor)
                .rate(rate)
                .status(ConversionQuote.QuoteStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(quoteTtlSeconds))
                .build();
        quoteRepository.save(quote);

        return QuoteResponse.from(quote);
    }

    // ---- Step 2: execute ---------------------------------------------------

    public TransactionResponse executeConversion(Long merchantId, ConversionRequest request) {
        TransactionService.ClaimResult claim = transactionService.claim(
                merchantId, TransactionType.CONVERSION, request.idempotencyKey(),
                "{\"quoteId\":\"" + request.quoteId() + "\"}");

        if (!claim.isNew()) {
            return TransactionResponse.from(claim.transaction());
        }

        try {
            conversionExecutor.execute(claim.transaction(), merchantId, request.quoteId());
        } catch (RuntimeException e) {
            transactionService.markFailed(claim.transaction().getId(), e.getMessage());
            throw e;
        }

        return TransactionResponse.from(transactionRepository.findById(claim.transaction().getId()).orElseThrow());
    }

    private BigDecimal minorToMajor(long amountMinor, int decimals) {
        return BigDecimal.valueOf(amountMinor).movePointLeft(decimals);
    }

    private long majorToMinor(BigDecimal amountMajor, int decimals) {
        return amountMajor.movePointRight(decimals).setScale(0, RoundingMode.DOWN).longValueExact();
    }
}
