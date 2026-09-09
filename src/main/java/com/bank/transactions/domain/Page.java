package com.bank.transactions.domain;

import java.util.List;

public record Page<T>(List<T> content, int page, int size, long totalElements) {

    public int totalPages() {
        return (int) Math.ceil((double) totalElements / size);
    }
}