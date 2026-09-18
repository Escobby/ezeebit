package com.ezeebit.wallet.exception;

/** Thrown whenever an operation would mix two different currencies without an explicit conversion. */
public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(String message) {
        super(message);
    }
}
