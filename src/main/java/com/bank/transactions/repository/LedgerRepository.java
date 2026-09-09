package com.bank.transactions.repository;

import com.bank.transactions.domain.LedgerEntry;
import com.bank.transactions.domain.Money;
import com.bank.transactions.domain.Page;
import com.bank.transactions.domain.TransactionType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Append-only: no update, no delete, not even package-private.
@Repository
public class LedgerRepository {

    private static final String COLUMNS =
            "entry_id, sequence, account_id, type, amount, balance_after, occurred_at, transfer_id";

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public LedgerEntry append(UUID accountId, TransactionType type, Money amount,
                              Money balanceAfter, UUID transferId) {
        // RETURNING brings back the sequence and occurred_at the database assigned, in the
        // same round trip. formatted() rather than concatenation: a text block strips the
        // trailing space after RETURNING.
        return jdbc.sql("""
                        INSERT INTO ledger_entries
                            (entry_id, account_id, type, amount, balance_after, transfer_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING %s""".formatted(COLUMNS))
                .params(UUID.randomUUID(), accountId, type.name(),
                        amount.amount(), balanceAfter.amount(), transferId)
                .query(this::toEntry)
                .single();
    }

    public Page<LedgerEntry> findByAccountId(UUID accountId, int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM ledger_entries WHERE account_id = ?")
                .param(accountId)
                .query(Long.class)
                .single();
        List<LedgerEntry> content = jdbc.sql("""
                        SELECT %s
                        FROM ledger_entries
                        WHERE account_id = ?
                        ORDER BY sequence
                        LIMIT ? OFFSET ?""".formatted(COLUMNS))
                .params(accountId, size, (long) page * size)
                .query(this::toEntry)
                .list();
        return new Page<>(content, page, size, total);
    }

    private LedgerEntry toEntry(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerEntry(
                rs.getObject("entry_id", UUID.class),
                rs.getLong("sequence"),
                rs.getObject("account_id", UUID.class),
                TransactionType.valueOf(rs.getString("type")),
                new Money(rs.getBigDecimal("amount")),
                new Money(rs.getBigDecimal("balance_after")),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("transfer_id", UUID.class));
    }
}