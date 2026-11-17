package com.deepwistom.atoms.video;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Budget guard for paid provider calls. {@code limitCny} is a global ceiling;
 * {@code videoReserveCny} and {@code textReserveCny} are configured per-task reservations (not
 * verified provider quotes) taken before any paid call. Failed submitted calls remain reserved;
 * only requests never submitted can release their reservation. When the limit is non-positive
 * every paid call is refused.
 */
@ConfigurationProperties("atoms.budget")
public record BudgetProperties(
        @DefaultValue("300.00") BigDecimal limitCny,
        @DefaultValue("6.00") BigDecimal videoReserveCny,
        @DefaultValue("1.00") BigDecimal textReserveCny) {}
