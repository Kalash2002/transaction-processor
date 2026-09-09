package com.bank.transactions.service;

import com.bank.transactions.domain.Account;
import com.bank.transactions.domain.LedgerEntry;
import com.bank.transactions.domain.Money;
import com.bank.transactions.domain.TransactionType;
import com.bank.transactions.support.PostgresTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 200 threads race to withdraw from an account that can fund exactly 40 of them.
// Three rounds, because one round is not always enough to catch an unlocked implementation.
@Tag("concurrency")
class ConcurrentWithdrawalTest extends PostgresTest {

    private static final int THREADS = 200;
    private static final String OPENING_BALANCE = "400.00";
    private static final String WITHDRAWAL = "10.00";
    private static final int AFFORDABLE = 40;

    @Autowired
    private AccountService service;

    @RepeatedTest(3)
    void should_never_let_concurrent_withdrawals_overdraw_the_account() throws Exception {
        Account account = service.openAccount(new Money(OPENING_BALANCE));
        AtomicInteger successes = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        service.withdraw(account.id(), new Money(WITHDRAWAL));
                        successes.incrementAndGet();
                    } catch (Exception expected) {
                        // 160 of these are supposed to happen
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        }

        // The count is the load-bearing assertion. "balance >= 0" passes on an implementation
        // where two threads both succeed and only one debit lands - and so does the database
        // CHECK constraint, because that balance is non-negative and consistent, just wrong.
        assertThat(successes.get()).isEqualTo(AFFORDABLE);
        assertThat(service.getAccount(account.id()).balance()).isEqualTo(Money.ZERO);

        List<LedgerEntry> entries = service.history(account.id(), 0, 100).content();
        assertThat(entries.stream().filter(e -> e.type() == TransactionType.WITHDRAWAL))
                .hasSize(AFFORDABLE);
        assertThat(entries.stream().map(LedgerEntry::sequence).distinct())
                .hasSize(entries.size());
    }
}