package com.bank.transactions.api;

import com.bank.transactions.domain.Account;
import com.bank.transactions.domain.BankingException;
import com.bank.transactions.domain.LedgerEntry;
import com.bank.transactions.domain.Money;
import com.bank.transactions.domain.Page;
import com.bank.transactions.domain.TransactionType;
import com.bank.transactions.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@ExtendWith(MockitoExtension.class)
class AccountApiTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final ObjectMapper json = new ObjectMapper();

    @Mock
    private AccountService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new AccountController(service), new TransferController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Account account(String balance) {
        return new Account(ACCOUNT_ID, new Money(balance));
    }

    private static LedgerEntry entry(UUID accountId, TransactionType type, String amount,
                                     String balanceAfter, UUID transferId) {
        return new LedgerEntry(UUID.randomUUID(), 1L, accountId, type, new Money(amount),
                new Money(balanceAfter), OCCURRED_AT, transferId);
    }

    @Test
    void should_open_an_account_and_return_its_balance() throws Exception {
        when(service.openAccount(new Money("100.00"))).thenReturn(account("100.00"));

        mvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("initialBalance", "100.00"))))
                .andExpect(status().isCreated())
                // A JSON number parses as a Double, so the matcher has to be numeric: a
                // String "100.00" fails against 100.0 with a confusing message.
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void should_return_an_account_by_id() throws Exception {
        when(service.getAccount(ACCOUNT_ID)).thenReturn(account("100.00"));

        mvc.perform(get("/accounts/" + ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void should_deposit_and_withdraw() throws Exception {
        when(service.deposit(eq(ACCOUNT_ID), any()))
                .thenReturn(entry(ACCOUNT_ID, TransactionType.DEPOSIT, "25.00", "125.00", null));
        when(service.withdraw(eq(ACCOUNT_ID), any()))
                .thenReturn(entry(ACCOUNT_ID, TransactionType.WITHDRAWAL, "25.00", "100.00", null));

        mvc.perform(post("/accounts/" + ACCOUNT_ID + "/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", "25.00"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.balanceAfter").value(125.00));

        mvc.perform(post("/accounts/" + ACCOUNT_ID + "/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", "25.00"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(100.00));
    }

    @Test
    void should_transfer_between_two_accounts() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(service.transfer(eq(ACCOUNT_ID), eq(OTHER_ID), any())).thenReturn(
                new AccountService.Transfer(transferId,
                        entry(ACCOUNT_ID, TransactionType.TRANSFER_OUT, "40.00", "60.00", transferId),
                        entry(OTHER_ID, TransactionType.TRANSFER_IN, "40.00", "40.00", transferId)));

        mvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fromAccountId", ACCOUNT_ID.toString(),
                                "toAccountId", OTHER_ID.toString(),
                                "amount", "40.00"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debit.balanceAfter").value(60.00))
                .andExpect(jsonPath("$.credit.balanceAfter").value(40.00));
    }

    @Test
    void should_return_paginated_history() throws Exception {
        when(service.history(ACCOUNT_ID, 0, 1)).thenReturn(new Page<>(
                List.of(entry(ACCOUNT_ID, TransactionType.ACCOUNT_OPENED, "100.00", "100.00", null)),
                0, 1, 1));

        mvc.perform(get("/accounts/" + ACCOUNT_ID + "/transactions")
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void should_return_409_for_insufficient_funds() throws Exception {
        when(service.withdraw(eq(ACCOUNT_ID), any())).thenThrow(new BankingException(
                BankingException.Code.INSUFFICIENT_FUNDS, "balance 10.00 cannot fund 10.01"));

        mvc.perform(post("/accounts/" + ACCOUNT_ID + "/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", "10.01"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void should_return_422_for_a_self_transfer() throws Exception {
        when(service.transfer(eq(ACCOUNT_ID), eq(ACCOUNT_ID), any())).thenThrow(
                new BankingException(BankingException.Code.SELF_TRANSFER,
                        "cannot transfer to the same account: " + ACCOUNT_ID));

        mvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fromAccountId", ACCOUNT_ID.toString(),
                                "toAccountId", ACCOUNT_ID.toString(),
                                "amount", "10.00"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SELF_TRANSFER"));
    }

    @Test
    void should_return_404_for_an_unknown_account() throws Exception {
        when(service.getAccount(ACCOUNT_ID)).thenThrow(new BankingException(
                BankingException.Code.ACCOUNT_NOT_FOUND, "no account " + ACCOUNT_ID));

        mvc.perform(get("/accounts/" + ACCOUNT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void should_return_400_for_a_malformed_account_id() throws Exception {
        // 400 rather than 404: the id itself is the problem, and a 404 would send the client
        // looking for an account that was never named.
        mvc.perform(get("/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void should_return_400_for_an_over_precise_amount() throws Exception {
        // Money rejects this in the controller, before the service is reached - which is why
        // there is no stubbing here.
        mvc.perform(post("/accounts/" + ACCOUNT_ID + "/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", "10.999"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_AMOUNT"));
    }

    @Test
    void should_return_400_when_the_amount_is_missing() throws Exception {
        mvc.perform(post("/accounts/" + ACCOUNT_ID + "/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }
}