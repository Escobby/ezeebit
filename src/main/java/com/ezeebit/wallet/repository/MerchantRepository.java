package com.ezeebit.wallet.repository;

import com.ezeebit.wallet.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
}
