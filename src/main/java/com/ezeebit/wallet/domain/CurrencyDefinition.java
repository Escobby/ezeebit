package com.ezeebit.wallet.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "currency_definitions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CurrencyDefinition {

    @Id
    @Column(name = "code", length = 10)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_type", nullable = false)
    private CurrencyType currencyType;

    /** How many decimal places this currency is expressed in, e.g. 2 for ZAR, 6 for USDT. */
    @Column(nullable = false)
    private int decimals;

    public enum CurrencyType { FIAT, CRYPTO }
}
