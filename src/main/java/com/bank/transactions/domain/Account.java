package com.bank.transactions.domain;

import java.util.UUID;

// The only mutable type. It owns the zero floor so no caller can skip the check.
public class Account {

    private final UUID id;
    private Money balance;

    public Account(UUID id, Money balance) {
        this.id = id;
        this.balance = balance;
    }

    public UUID id() {
        return id;
    }

    public Money balance() {
        return balance;
    }

    public void credit(Money amount) {
        balance = balance.plus(amount);
    }

    public void debit(Money amount) {
        if (balance.isLessThan(amount)) {
            throw new BankingException(BankingException.Code.INSUFFICIENT_FUNDS,
                    "balance " + balance + " cannot fund " + amount);
        }
        // Checks before mutating: an implementation that subtracted first would leave a
        // wrong balance behind on the exception path.
        balance = balance.minus(amount);
    }

    public boolean canDebit(Money amount) {
        return !balance.isLessThan(amount);
    }
}