package com.bank.transactions.repository;

import com.bank.transactions.domain.Account;
import com.bank.transactions.domain.BankingException;
import com.bank.transactions.domain.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account insert(Money openingBalance) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts (id, balance) VALUES (?, ?)")
                .params(id, openingBalance.amount())
                .update();
        return new Account(id, openingBalance);
    }

    // Plain read, no lock: fine for a query, never for a read you are about to write back.
    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT id, balance FROM accounts WHERE id = ?")
                .param(id)
                .query(this::toAccount)
                .optional();
    }

    public Account require(UUID id) {
        return findById(id).orElseThrow(() -> notFound(id));
    }

    // FOR UPDATE holds a write lock on the row until the transaction ends. This is the
    // whole concurrency guarantee, which is why it has a different name from findById.
    public Account requireForUpdate(UUID id) {
        return jdbc.sql("SELECT id, balance FROM accounts WHERE id = ? FOR UPDATE")
                .param(id)
                .query(this::toAccount)
                .optional()
                .orElseThrow(() -> notFound(id));
    }

    public void updateBalance(UUID id, Money balance) {
        jdbc.sql("UPDATE accounts SET balance = ? WHERE id = ?")
                .params(balance.amount(), id)
                .update();
    }

    private Account toAccount(ResultSet rs, int rowNum) throws SQLException {
        return new Account(rs.getObject("id", UUID.class), new Money(rs.getBigDecimal("balance")));
    }

    private static BankingException notFound(UUID id) {
        return new BankingException(BankingException.Code.ACCOUNT_NOT_FOUND, "no account " + id);
    }
}