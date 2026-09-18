package com.ezeebit.wallet.repository;

import com.ezeebit.wallet.domain.MoneyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionRepository extends JpaRepository<MoneyTransaction, Long> {

    Optional<MoneyTransaction> findByMerchantIdAndIdempotencyKey(Long merchantId, String idempotencyKey);

    Optional<MoneyTransaction> findByPublicId(String publicId);
}
