package com.ezeebit.wallet.exception;

public class QuoteAlreadyUsedException extends RuntimeException {
    public QuoteAlreadyUsedException(String message) {
        super(message);
    }
}
