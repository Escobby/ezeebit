package com.ezeebit.wallet.web;

import com.ezeebit.wallet.exception.*;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiError> handleInsufficientBalance(InsufficientBalanceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("INSUFFICIENT_BALANCE", e.getMessage()));
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ResponseEntity<ApiError> handleCurrencyMismatch(CurrencyMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("CURRENCY_MISMATCH", e.getMessage()));
    }

    @ExceptionHandler(QuoteExpiredException.class)
    public ResponseEntity<ApiError> handleQuoteExpired(QuoteExpiredException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("QUOTE_EXPIRED", e.getMessage()));
    }

    @ExceptionHandler(QuoteAlreadyUsedException.class)
    public ResponseEntity<ApiError> handleQuoteUsed(QuoteAlreadyUsedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("QUOTE_ALREADY_USED", e.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(ExchangeRateUnavailableException.class)
    public ResponseEntity<ApiError> handleRateUnavailable(ExchangeRateUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiError.of("RATE_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleValidation(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_ERROR", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "Something went wrong"));
    }
}
