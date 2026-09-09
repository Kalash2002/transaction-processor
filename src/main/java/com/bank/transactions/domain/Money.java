package com.bank.transactions.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

// Value object: non-negative, exactly 2 decimal places, immutable.
public final class Money implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private final BigDecimal amount;

    public Money(BigDecimal raw) {
        if (raw == null) {
            throw new BankingException(BankingException.Code.INVALID_AMOUNT, "amount is required");
        }
        if (raw.signum() < 0) {
            throw new BankingException(BankingException.Code.INVALID_AMOUNT,
                    "amount must not be negative: " + raw);
        }
        BigDecimal scaled;
        try {
            // UNNECESSARY throws rather than rounding. Not scale() > 2, which would wrongly
            // reject 10.100 - the same value as 10.10, and what a client sending a double emits.
            scaled = raw.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BankingException(BankingException.Code.INVALID_AMOUNT,
                    "amount must have at most 2 decimal places: " + raw);
        }
        this.amount = scaled;
    }

    public Money(String raw) {
        this(new BigDecimal(raw));
    }

    public BigDecimal amount() {
        return amount;
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        // compareTo, not BigDecimal.equals, which treats 10.0 and 10.00 as different.
        Money other = (Money) o;
        return amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}