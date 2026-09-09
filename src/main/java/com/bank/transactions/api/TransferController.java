package com.bank.transactions.api;

import com.bank.transactions.domain.Money;
import com.bank.transactions.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

// Its own resource rather than /accounts/{id}/transfers: a transfer belongs to both
// accounts, and nesting it would make the source privileged for no reason.
@RestController
@RequestMapping("/transfers")
public class TransferController {

    public record TransferRequest(@NotNull UUID fromAccountId, @NotNull UUID toAccountId,
                                  @NotNull BigDecimal amount) {
    }

    public record TransferResponse(UUID transferId,
                                   AccountController.EntryResponse debit,
                                   AccountController.EntryResponse credit) {
    }

    private final AccountService accountService;

    public TransferController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        AccountService.Transfer transfer = accountService.transfer(
                request.fromAccountId(), request.toAccountId(), new Money(request.amount()));
        return new TransferResponse(transfer.transferId(),
                AccountController.EntryResponse.from(transfer.debit()),
                AccountController.EntryResponse.from(transfer.credit()));
    }
}