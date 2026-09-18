package com.ezeebit.wallet.service.exchangerate;

import com.ezeebit.wallet.exception.ExchangeRateUnavailableException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stand-in for the real exchange-rate feed. Ships with rough, static
 * baseline rates (not live data - fine for this exercise) plus a small
 * amount of simulated jitter, so repeated quotes don't return identical
 * numbers, mirroring "its price keeps moving".
 *
 * It also demonstrates defensive handling of an unreliable dependency:
 * a small chance of a simulated timeout, and a sanity check that rejects
 * a rate outside a plausible band rather than trusting it blindly.
 */
@Component
public class MockExchangeRateClient implements ExchangeRateClient {

    // Approximate ZAR-equivalent prices, purely illustrative.
    private static final Map<String, BigDecimal> ZAR_PRICE = Map.of(
            "ZAR", BigDecimal.ONE,
            "NGN", new BigDecimal("0.012"),
            "KES", new BigDecimal("0.14"),
            "USDT", new BigDecimal("18.50"),
            "USDC", new BigDecimal("18.50")
    );

    private static final BigDecimal MIN_SANE_RATE = new BigDecimal("0.0000001");
    private static final BigDecimal MAX_SANE_RATE = new BigDecimal("100000000");

    /** Set > 0 to simulate the feed being flaky in tests/demos. 0 by default. */
    private volatile double simulatedTimeoutProbability = 0.0;

    @Override
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        if (ThreadLocalRandom.current().nextDouble() < simulatedTimeoutProbability) {
            throw new ExchangeRateUnavailableException(
                    "Simulated timeout calling exchange rate feed for " + fromCurrency + "->" + toCurrency);
        }

        BigDecimal fromInZar = ZAR_PRICE.get(fromCurrency);
        BigDecimal toInZar = ZAR_PRICE.get(toCurrency);
        if (fromInZar == null || toInZar == null) {
            throw new ExchangeRateUnavailableException(
                    "No rate available for pair " + fromCurrency + "->" + toCurrency);
        }

        // +/- 0.5% jitter so the "price keeps moving" between quotes.
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.01;
        BigDecimal rate = fromInZar
                .divide(toInZar, 12, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(jitter));

        return sanityCheck(rate, fromCurrency, toCurrency);
    }

    private BigDecimal sanityCheck(BigDecimal rate, String from, String to) {
        if (rate.compareTo(BigDecimal.ZERO) <= 0
                || rate.compareTo(MIN_SANE_RATE) < 0
                || rate.compareTo(MAX_SANE_RATE) > 0) {
            throw new ExchangeRateUnavailableException(
                    "Exchange rate feed returned an implausible rate for " + from + "->" + to + ": " + rate);
        }
        return rate;
    }

    /** Test/demo hook only. */
    public void setSimulatedTimeoutProbability(double p) {
        this.simulatedTimeoutProbability = p;
    }
}
