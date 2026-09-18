package com.ezeebit.wallet.repository;

import com.ezeebit.wallet.domain.ConversionQuote;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ConversionQuoteRepository extends JpaRepository<ConversionQuote, Long> {

    Optional<ConversionQuote> findByPublicId(String publicId);

    /**
     * Locked read used when *executing* a quote, so two concurrent attempts
     * to spend the same quote can't both see it as ACTIVE.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from ConversionQuote q where q.publicId = :publicId")
    Optional<ConversionQuote> findByPublicIdForUpdate(String publicId);
}
