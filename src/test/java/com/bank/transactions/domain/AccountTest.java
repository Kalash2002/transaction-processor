package com.bank.transactions.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private Account accountWith(String balance) {
        return new Account(UUID.randomUUID(), new Money(balance));
    }

    @Test
    void should_allow_spending_the_balance_down_to_exactly_zero() {
        Account account = accountWith("100.00");
        account.debit(new Money("100.00"));
        assertThat(account.balance()).isEqualTo(Money.ZERO);
    }

    @Test
    void should_reject_a_debit_one_penny_over_the_balance() {
        Account account = accountWith("100.00");
        assertThatThrownBy(() -> account.debit(new Money("100.01")))
                .isInstanceOf(BankingException.class);
    }

    @Test
    void should_leave_the_balance_untouched_when_a_debit_is_rejected() {
        Account account = accountWith("100.00");
        assertThatThrownBy(() -> account.debit(new Money("100.01")));
        assertThat(account.balance()).isEqualTo(new Money("100.00"));
    }
}