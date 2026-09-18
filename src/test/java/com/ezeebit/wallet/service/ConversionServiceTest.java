package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.*;
import com.ezeebit.wallet.exception.ExchangeRateUnavailableException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversionServiceTest {

    @Mock TransactionService transactionService;
    @Mock TransactionRepository transactionRepository;
    @Mock ConversionQuoteRepository quoteRepository;
    @Mock CurrencyDefinitionRepository currencyDefinitionRepository;
    @Mock ExchangeRateClient exchangeRateClient;
    @Mock ConversionExecutor conversionExecutor;

    ConversionService conversionService;

    @BeforeEach
    void setUp() {
        conversionService = new ConversionService(transactionService, transactionRepository, quoteRepository,
                currencyDefinitionRepository, exchangeRateClient, conversionExecutor);
        ReflectionTestUtils.setField(conversionService, "quoteTtlSeconds", 20L);
    }

    @Test
    void createQuote_happyPath_convertsUsingCurrencyDecimalsAndRate() {
        // 100.00 USDT (decimals=6, so 100_000000 minor units) at rate 18.50 -> 1850.00 ZAR (decimals=2)
        when(currencyDefinitionRepository.findById("USDT"))
                .thenReturn(Optional.of(currency("USDT", 6)));
        when(currencyDefinitionRepository.findById("ZAR"))
                .thenReturn(Optional.of(currency("ZAR", 2)));
        when(exchangeRateClient.getRate("USDT", "ZAR")).thenReturn(new BigDecimal("18.50"));

        QuoteResponse quote = conversionService.createQuote(1L,
                new QuoteRequest("USDT", "ZAR", 100_000_000L));

        assertThat(quote.toAmountMinor()).isEqualTo(185_000L); // 1850.00 ZAR in cents
        assertThat(quote.rate()).isEqualByComparingTo("18.50");
    }

    @Test
    void createQuote_roundsDownInThePlatformsFavour() {
        // 1 minor unit of USDT (0.000001) at a rate that produces a fractional
        // ZAR cent must round DOWN, never up - the platform never pays out
        // more than the rate actually justifies.
        when(currencyDefinitionRepository.findById("USDT")).thenReturn(Optional.of(currency("USDT", 6)));
        when(currencyDefinitionRepository.findById("ZAR")).thenReturn(Optional.of(currency("ZAR", 2)));
        when(exchangeRateClient.getRate("USDT", "ZAR")).thenReturn(new BigDecimal("18.999"));

        QuoteResponse quote = conversionService.createQuote(1L, new QuoteRequest("USDT", "ZAR", 1000L)); // 0.001 USDT

        // 0.001 * 18.999 = 0.018999 ZAR -> floors to 0.01 ZAR = 1 minor unit
        assertThat(quote.toAmountMinor()).isEqualTo(1L);
    }

    @Test
    void createQuote_sameCurrencyBothSides_rejected() {
        assertThatThrownBy(() -> conversionService.createQuote(1L, new QuoteRequest("ZAR", "ZAR", 100L)))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(exchangeRateClient);
    }

    @Test
    void createQuote_unknownCurrency_throwsNotFound() {
        when(currencyDefinitionRepository.findById("XYZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversionService.createQuote(1L, new QuoteRequest("XYZ", "ZAR", 100L)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createQuote_whenRateFeedFails_propagatesExchangeRateUnavailable() {
        when(currencyDefinitionRepository.findById("USDT")).thenReturn(Optional.of(currency("USDT", 6)));
        when(currencyDefinitionRepository.findById("ZAR")).thenReturn(Optional.of(currency("ZAR", 2)));
        when(exchangeRateClient.getRate("USDT", "ZAR"))
                .thenThrow(new ExchangeRateUnavailableException("timeout"));

        assertThatThrownBy(() -> conversionService.createQuote(1L, new QuoteRequest("USDT", "ZAR", 100L)))
                .isInstanceOf(ExchangeRateUnavailableException.class);
        verify(quoteRepository, never()).save(any());
    }

    @Test
    void executeConversion_replayedIdempotencyKey_neverCallsExecutorTwice() {
        MoneyTransaction alreadyCompleted = MoneyTransaction.builder()
                .id(5L).merchantId(1L).type(TransactionType.CONVERSION).status(TransactionStatus.COMPLETED)
                .idempotencyKey("k1").build();
        when(transactionService.claim(eq(1L), eq(TransactionType.CONVERSION), eq("k1"), any()))
                .thenReturn(new TransactionService.ClaimResult(alreadyCompleted, false));

        TransactionResponse response = conversionService.executeConversion(1L, new ConversionRequest("quote-1", "k1"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        verifyNoInteractions(conversionExecutor);
    }

    private CurrencyDefinition currency(String code, int decimals) {
        return CurrencyDefinition.builder().code(code).decimals(decimals)
                .currencyType(CurrencyDefinition.CurrencyType.CRYPTO).build();
    }
}
