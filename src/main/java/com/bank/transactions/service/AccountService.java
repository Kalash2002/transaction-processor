package com.bank.transactions.service;

import com.bank.transactions.domain.Account;
import com.bank.transactions.domain.BankingException;
import com.bank.transactions.domain.LedgerEntry;
import com.bank.transactions.domain.Money;
import com.bank.transactions.domain.Page;
import com.bank.transactions.domain.TransactionType;
import com.bank.transactions.repository.AccountRepository;
import com.bank.transactions.repository.LedgerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;


@Service
public class AccountService {

    public record Transfer(UUID transferId, LedgerEntry debit, LedgerEntry credit) {
    }

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final BigDecimal maxTransactionAmount;
    private final int defaultPageSize;
    private final int maxPageSize;

    public AccountService(AccountRepository accounts, LedgerRepository ledger,
                          @Value("${banking.max-transaction-amount}") BigDecimal maxTransactionAmount,
                          @Value("${banking.default-page-size}") int defaultPageSize,
                          @Value("${banking.max-page-size}") int maxPageSize) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.maxTransactionAmount = maxTransactionAmount;
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
    }

    // The opening balance is recorded as an entry, so replaying the ledger from zero always
    // reconstructs the balance. It is exempt from the ceiling and may be zero.
    @Transactional
    public Account openAccount(Money openingBalance) {
        Account account = accounts.insert(openingBalance);
        ledger.append(account.id(), TransactionType.ACCOUNT_OPENED, openingBalance,
                account.balance(), null);
        return account;
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID accountId) {
        return accounts.require(accountId);
    }

    @Transactional
    public LedgerEntry deposit(UUID accountId, Money amount) {
        requireTransactionAmount(amount);

        Account account = accounts.requireForUpdate(accountId);
        account.credit(amount);
        accounts.updateBalance(account.id(), account.balance());
        return ledger.append(account.id(), TransactionType.DEPOSIT, amount, account.balance(), null);
    }

    @Transactional
    public LedgerEntry withdraw(UUID accountId, Money amount) {
        requireTransactionAmount(amount);

        // The affordability check is inside Account.debit and runs after the lock is held,
        // so there is no window between deciding and applying.
        Account account = accounts.requireForUpdate(accountId);
        account.debit(amount);
        accounts.updateBalance(account.id(), account.balance());
        return ledger.append(account.id(), TransactionType.WITHDRAWAL, amount, account.balance(), null);
    }

    @Transactional
    public Transfer transfer(UUID fromAccountId, UUID toAccountId, Money amount) {
        requireTransactionAmount(amount);
        if (fromAccountId.equals(toAccountId)) {
            throw new BankingException(BankingException.Code.SELF_TRANSFER,
                    "cannot transfer to the same account: " + fromAccountId);
        }

        // Lock both rows lowest id first, whichever side is the source. Locking
        // source-then-destination deadlocks as soon as A->B runs alongside B->A.
        boolean sourceFirst = fromAccountId.compareTo(toAccountId) < 0;
        Account first = accounts.requireForUpdate(sourceFirst ? fromAccountId : toAccountId);
        Account second = accounts.requireForUpdate(sourceFirst ? toAccountId : fromAccountId);
        Account source = sourceFirst ? first : second;
        Account destination = sourceFirst ? second : first;

        if (!source.canDebit(amount)) {
            throw new BankingException(BankingException.Code.INSUFFICIENT_FUNDS,
                    "balance " + source.balance() + " cannot fund " + amount);
        }
        source.debit(amount);
        destination.credit(amount);
        accounts.updateBalance(source.id(), source.balance());
        accounts.updateBalance(destination.id(), destination.balance());

        UUID transferId = UUID.randomUUID();
        LedgerEntry debit = ledger.append(source.id(), TransactionType.TRANSFER_OUT, amount,
                source.balance(), transferId);
        LedgerEntry credit = ledger.append(destination.id(), TransactionType.TRANSFER_IN, amount,
                destination.balance(), transferId);
        return new Transfer(transferId, debit, credit);
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntry> history(UUID accountId, int page, Integer size) {
        accounts.require(accountId);
        int effectiveSize = size == null ? defaultPageSize : size;
        if (page < 0) {
            throw new BankingException(BankingException.Code.INVALID_PAGE_REQUEST,
                    "page must not be negative");
        }
        // Rejected, not clamped: asking for 1000 and silently getting 100 makes a client
        // believe it has seen everything.
        if (effectiveSize < 1 || effectiveSize > maxPageSize) {
            throw new BankingException(BankingException.Code.INVALID_PAGE_REQUEST,
                    "size must be between 1 and " + maxPageSize);
        }
        return ledger.findByAccountId(accountId, page, effectiveSize);
    }

    private void requireTransactionAmount(Money amount) {
        if (amount.isZero()) {
            throw new BankingException(BankingException.Code.INVALID_AMOUNT,
                    "amount must be greater than zero");
        }
        if (amount.amount().compareTo(maxTransactionAmount) > 0) {
            throw new BankingException(BankingException.Code.INVALID_AMOUNT,
                    "amount exceeds the per-transaction limit of " + maxTransactionAmount);
        }
    }
}