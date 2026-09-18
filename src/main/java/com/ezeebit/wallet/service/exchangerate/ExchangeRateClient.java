package com.ezeebit.wallet.service.exchangerate;

import java.math.BigDecimal;

/**
 * Stands in for the exchange-rate feed the brief says already exists.
 * The brief warns it "can be slow and its price keeps moving" and that we
 * should show how we'd handle a bad rate or a timeout - see
 * {@link MockExchangeRateClient} for the simulated failure modes, and
 * ConversionService for how a quote's rate is locked and validated.
 */
public interface ExchangeRateClient {

    /**
     * @return the price of one unit of {@code fromCurrency} expressed in
     *         {@code toCurrency}, e.g. rate(USDT, ZAR) ~= 18.50
     * @throws com.ezeebit.wallet.exception.ExchangeRateUnavailableException
     *         if the feed times out, errors, or returns a rate that fails
     *         a basic sanity check (non-positive, wildly out of range).
     */
    BigDecimal getRate(String fromCurrency, String toCurrency);
}
