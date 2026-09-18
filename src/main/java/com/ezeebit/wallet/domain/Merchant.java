package com.ezeebit.wallet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "merchants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Merchant {

    /** Reserved id for Ezeebit's own clearing accounts. Not a real customer. */
    public static final long SYSTEM_MERCHANT_ID = 0L;

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
