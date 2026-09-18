package com.ezeebit.wallet.web;

import java.time.Instant;

public record ApiError(String error, String message, Instant timestamp) {
    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now());
    }
}
