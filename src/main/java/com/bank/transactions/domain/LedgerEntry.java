package com.bank.transactions.domain;

import java.time.Instant;
import java.util.UUID;

// One movement on one account. Immutable, and the repository has no update or delete:
// a correction is a new compensating entry.
// sequence is assigned by the database, because timestamps cannot order concurrent entries.
public record LedgerEntry(
        UUID entryId,
        long sequence,
        UUID accountId,
        TransactionType type,
        Money amount,
        Money balanceAfter,
        Instant occurredAt,
        UUID transferId) {
}