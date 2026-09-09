package com.bank.transactions.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void should_accept_a_two_place_amount() {
        assertThat(new Money("10.25").amount()).isEqualByComparingTo("10.25");
    }

    @Test
    void should_reject_an_amount_with_more_than_two_decimal_places() {
        assertThatThrownBy(() -> new Money("10.999"))
                .isInstanceOf(BankingException.class)
                .hasMessageContaining("2 decimal places");
    }

    @Test
    void should_accept_a_trailing_zero_that_is_really_two_places() {
        // 10.100 has scale 3 but is exactly 10.10. A scale() > 2 check rejects it wrongly,
        // and clients serialising from a double send these constantly.
        assertThat(new Money("10.100")).isEqualTo(new Money("10.10"));
    }

    @Test
    void should_reject_a_negative_amount() {
        assertThatThrownBy(() -> new Money("-0.01"))
                .isInstanceOf(BankingException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void should_reject_a_null_amount() {
        assertThatThrownBy(() -> new Money((BigDecimal) null))
                .isInstanceOf(BankingException.class);
    }

    @Test
    void should_add_and_subtract_exactly() {
        // 0.1 + 0.2 is 0.30000000000000004 in binary floating point.
        assertThat(new Money("0.10").plus(new Money("0.20"))).isEqualTo(new Money("0.30"));
        assertThat(new Money("10.00").minus(new Money("9.99"))).isEqualTo(new Money("0.01"));
    }

    @Test
    void should_treat_the_same_value_at_different_scales_as_equal() {
        assertThat(new Money(new BigDecimal("10.0"))).isEqualTo(new Money(new BigDecimal("10.00")));
    }
}