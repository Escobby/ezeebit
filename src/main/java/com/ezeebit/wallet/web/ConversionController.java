package com.ezeebit.wallet.web;

import com.ezeebit.wallet.service.ConversionService;
import com.ezeebit.wallet.web.dto.ConversionRequest;
import com.ezeebit.wallet.web.dto.QuoteRequest;
import com.ezeebit.wallet.web.dto.QuoteResponse;
import com.ezeebit.wallet.web.dto.TransactionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/merchants/{merchantId}")
@RequiredArgsConstructor
public class ConversionController {

    private final ConversionService conversionService;

    @PostMapping("/quotes")
    public ResponseEntity<QuoteResponse> quote(@PathVariable Long merchantId, @Valid @RequestBody QuoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conversionService.createQuote(merchantId, request));
    }

    @PostMapping("/conversions")
    public ResponseEntity<TransactionResponse> convert(@PathVariable Long merchantId, @Valid @RequestBody ConversionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conversionService.executeConversion(merchantId, request));
    }
}
