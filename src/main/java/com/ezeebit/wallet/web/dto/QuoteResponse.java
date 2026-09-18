package com.ezeebit.wallet.web.dto;

import com.ezeebit.wallet.domain.ConversionQuote;

import java.math.BigDecimal;
import java.time.Instant;

public record QuoteResponse(
        String quoteId,
        String fromCurrency,
        String toCurrency,
        long fromAmountMinor,
        long toAmountMinor,
        BigDecimal rate,
        Instant expiresAt
) {
    public static QuoteResponse from(ConversionQuote q) {
        return new QuoteResponse(q.getPublicId(), q.getFromCurrency(), q.getToCurrency(),
                q.getFromAmountMinor(), q.getToAmountMinor(), q.getRate(), q.getExpiresAt());
    }
}
