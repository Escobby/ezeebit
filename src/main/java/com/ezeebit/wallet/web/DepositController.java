package com.ezeebit.wallet.web;

import com.ezeebit.wallet.service.DepositService;
import com.ezeebit.wallet.web.dto.DepositRequest;
import com.ezeebit.wallet.web.dto.TransactionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/merchants/{merchantId}/deposits")
@RequiredArgsConstructor
public class DepositController {

    private final DepositService depositService;

    @PostMapping
    public ResponseEntity<TransactionResponse> deposit(@PathVariable Long merchantId,
                                                         @Valid @RequestBody DepositRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(depositService.deposit(merchantId, request));
    }
}
