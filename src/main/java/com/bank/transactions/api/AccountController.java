package com.bank.transactions.api;

import com.bank.transactions.domain.Account;
import com.bank.transactions.domain.LedgerEntry;
import com.bank.transactions.domain.Money;
import com.bank.transactions.domain.Page;
import com.bank.transactions.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Parse, delegate, map. No business rules here.
@RestController
@RequestMapping("/accounts")
public class AccountController {

    // @NotNull only. Positivity and scale live in Money; a @DecimalMin here would be a
    // second rule that can disagree, and it wins silently before the domain runs.
    public record OpenAccountRequest(@NotNull BigDecimal initialBalance) {
    }

    public record AmountRequest(@NotNull BigDecimal amount) {
    }

    public record AccountResponse(UUID accountId, BigDecimal balance) {
        static AccountResponse from(Account account) {
            return new AccountResponse(account.id(), account.balance().amount());
        }
    }

    public record EntryResponse(UUID entryId, long sequence, String type, BigDecimal amount,
                                BigDecimal balanceAfter, Instant occurredAt, UUID transferId) {
        static EntryResponse from(LedgerEntry entry) {
            return new EntryResponse(entry.entryId(), entry.sequence(), entry.type().name(),
                    entry.amount().amount(), entry.balanceAfter().amount(),
                    entry.occurredAt(), entry.transferId());
        }
    }

    public record PageResponse(List<EntryResponse> content, int page, int size,
                               long totalElements, int totalPages) {
        static PageResponse from(Page<LedgerEntry> page) {
            return new PageResponse(page.content().stream().map(EntryResponse::from).toList(),
                    page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@Valid @RequestBody OpenAccountRequest request) {
        return AccountResponse.from(accountService.openAccount(new Money(request.initialBalance())));
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable UUID accountId) {
        return AccountResponse.from(accountService.getAccount(accountId));
    }

    // POST to a plural noun returning 201: each one creates a ledger entry, so these are
    // sub-resources. /deposit would be an RPC call wearing a REST costume.
    @PostMapping("/{accountId}/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryResponse deposit(@PathVariable UUID accountId,
                                 @Valid @RequestBody AmountRequest request) {
        return EntryResponse.from(accountService.deposit(accountId, new Money(request.amount())));
    }

    @PostMapping("/{accountId}/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryResponse withdraw(@PathVariable UUID accountId,
                                  @Valid @RequestBody AmountRequest request) {
        return EntryResponse.from(accountService.withdraw(accountId, new Money(request.amount())));
    }

    @GetMapping("/{accountId}/transactions")
    public PageResponse history(@PathVariable UUID accountId,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(required = false) Integer size) {
        return PageResponse.from(accountService.history(accountId, page, size));
    }
}