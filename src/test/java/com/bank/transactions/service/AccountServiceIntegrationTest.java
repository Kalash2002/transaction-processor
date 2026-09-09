package com.bank.transactions.service;

import com.bank.transactions.domain.Account;
import com.bank.transactions.domain.BankingException;
import com.bank.transactions.domain.Money;
import com.bank.transactions.domain.TransactionType;
import com.bank.transactions.repository.AccountRepository;
import com.bank.transactions.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final BigDecimal MAX_TRANSACTION = new BigDecimal("1000000000.00");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;


    private static final UUID LOWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIGHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final Money FIFTY = new Money("50.00");
    private static final Money HUNDRED = new Money("100.00");

    @Mock
    private AccountRepository accounts;

    @Mock
    private LedgerRepository ledger;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(accounts, ledger, MAX_TRANSACTION, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
    }

    private static Account accountWith(UUID id, String balance) {
        return new Account(id, new Money(balance));
    }

    @Test
    void should_record_the_opening_balance_as_the_first_ledger_entry() {
        when(accounts.insert(HUNDRED)).thenReturn(accountWith(LOWER, "100.00"));

        service.openAccount(HUNDRED);

        verify(ledger).append(LOWER, TransactionType.ACCOUNT_OPENED, HUNDRED, HUNDRED, null);
    }

    @Test
    void should_allow_an_account_to_open_with_a_zero_balance() {
        when(accounts.insert(Money.ZERO)).thenReturn(accountWith(LOWER, "0.00"));

        service.openAccount(Money.ZERO);

        // The opening entry is exempt from the "greater than zero" rule: an account that opens
        // empty still needs the entry, or replaying the ledger would not reconstruct it.
        verify(ledger).append(LOWER, TransactionType.ACCOUNT_OPENED, Money.ZERO, Money.ZERO, null);
    }

    @Test
    void should_reject_a_zero_deposit_without_touching_the_database() {
        assertThatThrownBy(() -> service.deposit(LOWER, Money.ZERO))
                .isInstanceOf(BankingException.class)
                .hasMessageContaining("greater than zero");

        // The point of the mock: the amount is refused before a row is read, let alone locked.
        verifyNoInteractions(accounts, ledger);
    }

    @Test
    void should_reject_an_amount_over_the_ceiling_without_touching_the_database() {
        assertThatThrownBy(() -> service.deposit(LOWER, new Money("1000000000.01")))
                .isInstanceOf(BankingException.class)
                .hasMessageContaining("per-transaction limit");

        verifyNoInteractions(accounts, ledger);
    }

    @Test
    void should_lock_the_row_then_update_the_balance_then_append_the_entry() {
        when(accounts.requireForUpdate(LOWER)).thenReturn(accountWith(LOWER, "100.00"));

        service.deposit(LOWER, FIFTY);

        InOrder order = inOrder(accounts, ledger);
        order.verify(accounts).requireForUpdate(LOWER);
        order.verify(accounts).updateBalance(LOWER, new Money("150.00"));
        order.verify(ledger).append(LOWER, TransactionType.DEPOSIT, FIFTY, new Money("150.00"), null);
    }

    @Test
    void should_allow_a_withdrawal_of_the_whole_balance() {
        when(accounts.requireForUpdate(LOWER)).thenReturn(accountWith(LOWER, "100.00"));

        service.withdraw(LOWER, HUNDRED);

        verify(accounts).updateBalance(LOWER, Money.ZERO);
        verify(ledger).append(LOWER, TransactionType.WITHDRAWAL, HUNDRED, Money.ZERO, null);
    }

    @Test
    void should_write_nothing_when_a_withdrawal_exceeds_the_balance() {
        when(accounts.requireForUpdate(LOWER)).thenReturn(accountWith(LOWER, "40.00"));

        assertThatThrownBy(() -> service.withdraw(LOWER, HUNDRED))
                .isInstanceOf(BankingException.class);

        verify(accounts, never()).updateBalance(any(), any());
        verifyNoInteractions(ledger);
    }

    @Test
    void should_not_write_an_entry_when_the_account_does_not_exist() {
        when(accounts.requireForUpdate(LOWER)).thenThrow(new BankingException(
                BankingException.Code.ACCOUNT_NOT_FOUND, "no account " + LOWER));

        assertThatThrownBy(() -> service.deposit(LOWER, FIFTY))
                .isInstanceOf(BankingException.class)
                .hasMessageContaining("no account");

        verifyNoInteractions(ledger);
    }

    @Test
    void should_reject_a_self_transfer_before_taking_any_lock() {
        assertThatThrownBy(() -> service.transfer(LOWER, LOWER, FIFTY))
                .isInstanceOf(BankingException.class)
                .hasMessageContaining("same account");

        verifyNoInteractions(accounts, ledger);
    }

    @Test
    void should_lock_the_lower_id_first_when_transferring_from_lower_to_higher() {
        when(accounts.requireForUpdate(LOWER)).thenReturn(accountWith(LOWER, "100.00"));
        when(accounts.requireForUpdate(HIGHER)).thenReturn(accountWith(HIGHER, "100.00"));

        service.transfer(LOWER, HIGHER, FIFTY);

        InOrder order = inOrder(accounts);
        order.verify(accounts).requireForUpdate(LOWER);
        order.verify(accounts).requireForUpdate(HIGHER);
    }

    @Test
    void should_lock_the_lower_id_first_when_transferring_from_higher_to_lower() {
        when(accounts.requireForUpdate(LOWER)).thenReturn(accountWith(LOWER, "100.00"));
        when(accounts.requireForUpdate(HIGHER)).thenReturn(accountWith(HIGHER, "100.00"));

        service.transfer(HIGHER, LOWER, FIFTY);

        // Same order as the test above, with the money going the other way. This is the
        // assertion the Postgres tests cannot make: there, a wrong order shows up only as a
        // stall, and only sometimes.
        InOrder order = inOrder(accounts);
        order.verify(accounts).requireForUpdate(LOWER);
        order.verify(accounts).requireForUpdate(HIGHER);
    }

    @Test
    void should_move_money_and_write_both_legs_under_one_transfer_id() {
        when(accounts.requireForUpdate(LOWER)).thenReturn(accountWith(LOWER, "100.00"));
        when(accounts.requireForUpdate(HIGHER)).thenReturn(accountWith(HIGHER, "0.00"));

        service.transfer(LOWER, HIGHER, new Money("40.00"));

        verify(accounts).updateBalance(LOWER, new Money("60.00"));
        verify(accounts).updateBalance(HIGHER, new Money("40.00"));

        ArgumentCaptor<UUID> transferId = ArgumentCaptor.forClass(UUID.class);
        verify(ledger).append(eq(LOWER), eq(TransactionType.TRANSFER_OUT), any(), any(),
                transferId.capture());
        verify(ledger).append(eq(HIGHER), eq(TransactionType.TRANSFER_IN), any(), any(),
                transferId.capture());
        // One id on both legs is the only thing joining them - there is no transfer row.
        assertThat(transferId.getAllValues()).hasSize(2)
                .satisfies(ids -> assertThat(ids.get(0)).isEqualTo(ids.get(1)));
    }

    @Test
    void should_write_nothing_when_the_source_cannot_fund_the_transfer() {
        when(accounts.requireForUpdate(LOWER)).thenReturn(accountWith(LOWER, "10.00"));
        when(accounts.requireForUpdate(HIGHER)).thenReturn(accountWith(HIGHER, "100.00"));

        assertThatThrownBy(() -> service.transfer(LOWER, HIGHER, FIFTY))
                .isInstanceOf(BankingException.class)
                .hasMessageContaining("cannot fund");

        // Both accounts are validated before either is mutated, so there is nothing to reverse.
        verify(accounts, never()).updateBalance(any(), any());
        verifyNoInteractions(ledger);
    }

    @Test
    void should_use_the_default_page_size_when_none_is_given() {
        service.history(LOWER, 0, null);

        verify(ledger).findByAccountId(LOWER, 0, DEFAULT_PAGE_SIZE);
    }

    @Test
    void should_pass_the_requested_page_and_size_through_to_the_ledger() {
        service.history(LOWER, 2, 5);

        verify(ledger).findByAccountId(LOWER, 2, 5);
    }

    @Test
    void should_reject_a_page_size_over_the_maximum_without_reading_the_ledger() {
        assertThatThrownBy(() -> service.history(LOWER, 0, MAX_PAGE_SIZE + 1))
                .isInstanceOf(BankingException.class)
                .hasMessageContaining("between 1 and");

        verifyNoInteractions(ledger);
    }

    @Test
    void should_reject_a_negative_page_without_reading_the_ledger() {
        assertThatThrownBy(() -> service.history(LOWER, -1, null))
                .isInstanceOf(BankingException.class)
                .hasMessageContaining("must not be negative");

        verifyNoInteractions(ledger);
    }
}