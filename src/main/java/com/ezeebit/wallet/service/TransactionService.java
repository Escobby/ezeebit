package com.ezeebit.wallet.service;

import com.ezeebit.wallet.domain.MoneyTransaction;
import com.ezeebit.wallet.domain.TransactionStatus;
import com.ezeebit.wallet.domain.TransactionType;
import com.ezeebit.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public record ClaimResult(MoneyTransaction transaction, boolean isNew) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claim(Long merchantId, TransactionType type, String idempotencyKey, String metadataJson) {
        // 1. Read check first to avoid poisoning the Hibernate session on standard duplicate calls
        Optional<MoneyTransaction> existing = transactionRepository
                .findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);

        if (existing.isPresent()) {
            return new ClaimResult(existing.get(), false);
        }

        // 2. Persist new transaction if none exists
        try {
            MoneyTransaction transaction = MoneyTransaction.builder()
                    .merchantId(merchantId)
                    .type(type)
                    .status(TransactionStatus.PENDING)
                    .idempotencyKey(idempotencyKey)
                    .metadataJson(metadataJson)
                    .build();

            MoneyTransaction saved = transactionRepository.saveAndFlush(transaction);
            return new ClaimResult(saved, true);
        } catch (DataIntegrityViolationException duplicate) {
            // Do NOT query transactionRepository here. The Session is rollback-only.
            // Throwing allows REQUIRES_NEW to rollback cleanly so the caller can retry or fetch.
            throw duplicate;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(Long transactionId) {
        MoneyTransaction tx = transactionRepository.getReferenceById(transactionId);
        tx.setStatus(TransactionStatus.COMPLETED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long transactionId, String reason) {
        MoneyTransaction tx = transactionRepository.getReferenceById(transactionId);
        tx.setStatus(TransactionStatus.FAILED);
        tx.setMetadataJson(appendFailure(tx.getMetadataJson(), reason));
    }

    private String appendFailure(String existingMetadata, String reason) {
        String safeReason = reason == null ? "unknown error" : reason.replace("\"", "'");
        String failureField = "\"failureReason\":\"" + safeReason + "\"";
        if (existingMetadata == null || existingMetadata.isBlank()) {
            return "{" + failureField + "}";
        }
        return existingMetadata.replaceFirst("}$", "," + failureField + "}");
    }
}