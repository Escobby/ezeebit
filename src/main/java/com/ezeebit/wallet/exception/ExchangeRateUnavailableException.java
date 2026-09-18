package com.ezeebit.wallet.exception;

/** The exchange-rate feed timed out, errored, or returned a rate that failed sanity checks. */
public class ExchangeRateUnavailableException extends RuntimeException {
    public ExchangeRateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
    public ExchangeRateUnavailableException(String message) {
        super(message);
    }
}
