package com.ezeebit.wallet.repository;

import com.ezeebit.wallet.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByMerchantIdAndCurrencyCode(Long merchantId, String currencyCode);

    List<Account> findByMerchantId(Long merchantId);

    /**
     * Locks the account row for the duration of the current transaction
     * (SELECT ... FOR UPDATE). Every method that mutates a balance must go
     * through this, in a single @Transactional method, so that concurrent
     * requests against the same account (duplicate withdrawals, a deposit
     * racing a conversion, two withdrawals fired at once) are serialized by
     * the database instead of racing in application memory.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.merchantId = :merchantId and a.currencyCode = :currencyCode")
    Optional<Account> findForUpdate(Long merchantId, String currencyCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(Long id);
}
