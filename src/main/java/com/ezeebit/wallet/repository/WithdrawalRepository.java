package com.ezeebit.wallet.repository;

import com.ezeebit.wallet.domain.Withdrawal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {

    Optional<Withdrawal> findByMerchantIdAndIdempotencyKey(Long merchantId, String idempotencyKey);

    Optional<Withdrawal> findByPublicId(String publicId);

    Optional<Withdrawal> findByTransactionId(Long transactionId);

    /** Locked read used when applying an async payout callback, so a duplicate
     * webhook delivery can't apply the same success/failure transition twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Withdrawal w where w.publicId = :publicId")
    Optional<Withdrawal> findByPublicIdForUpdate(String publicId);
}
