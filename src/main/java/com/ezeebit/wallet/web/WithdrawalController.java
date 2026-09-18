package com.ezeebit.wallet.web;

import com.ezeebit.wallet.service.WithdrawalService;
import com.ezeebit.wallet.web.dto.WithdrawalRequest;
import com.ezeebit.wallet.web.dto.WithdrawalResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/merchants/{merchantId}/withdrawals")
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    @PostMapping
    public ResponseEntity<WithdrawalResponse> withdraw(@PathVariable Long merchantId,
                                                         @Valid @RequestBody WithdrawalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(withdrawalService.initiateWithdrawal(merchantId, request));
    }
}
