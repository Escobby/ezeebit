package com.ezeebit.wallet.repository;

import com.ezeebit.wallet.domain.CurrencyDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CurrencyDefinitionRepository extends JpaRepository<CurrencyDefinition, String> {
}
