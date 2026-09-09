package com.bank.transactions.domain;

public class BankingException extends RuntimeException {

    public enum Code {
        INVALID_AMOUNT,
        INVALID_PAGE_REQUEST,
        ACCOUNT_NOT_FOUND,
        INSUFFICIENT_FUNDS,
        SELF_TRANSFER
    }

    private final Code code;

    public BankingException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}