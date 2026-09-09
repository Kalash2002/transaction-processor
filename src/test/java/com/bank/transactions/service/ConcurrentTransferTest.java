package com.bank.transactions.service;

import com.bank.transactions.domain.Account;
import com.bank.transactions.domain.Money;
import com.bank.transactions.support.PostgresTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

// 200 transfers, half in each direction between one pair of accounts: the shape that
// deadlocks an implementation locking source-then-destination.
@Tag("concurrency")
class ConcurrentTransferTest extends PostgresTest {

    private static final int PAIRS = 100;
    private static final String OPENING_BALANCE = "1000.00";
    private static final String AMOUNT = "1.00";

    @Autowired
    private AccountService service;

    @Test
    void should_not_deadlock_when_transfers_run_in_both_directions() {
        // Preemptive: with a wrong lock order Postgres needs deadlock_timeout (1s) to notice
        // each cycle, so the symptom is a stall rather than a clean error.
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            Account a = service.openAccount(new Money(OPENING_BALANCE));
            Account b = service.openAccount(new Money(OPENING_BALANCE));
            AtomicInteger deadlocks = new AtomicInteger();
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(PAIRS * 2);

            try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
                for (int i = 0; i < PAIRS; i++) {
                    pool.submit(transferTask(a, b, startGate, finished, deadlocks));
                    pool.submit(transferTask(b, a, startGate, finished, deadlocks));
                }
                startGate.countDown();
                assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(deadlocks.get()).isZero();
            // Finishing only proves the threads finished. Conservation proves they finished
            // correctly: a debit committed without its credit shows up here and nowhere else.
            Money total = service.getAccount(a.id()).balance()
                    .plus(service.getAccount(b.id()).balance());
            assertThat(total).isEqualTo(new Money(OPENING_BALANCE).plus(new Money(OPENING_BALANCE)));
        });
    }

    private Runnable transferTask(Account from, Account to, CountDownLatch startGate,
                                  CountDownLatch finished, AtomicInteger deadlocks) {
        return () -> {
            try {
                startGate.await();
                service.transfer(from.id(), to.id(), new Money(AMOUNT));
            } catch (CannotAcquireLockException e) {
                deadlocks.incrementAndGet();
            } catch (Exception ignored) {
                // insufficient funds is legitimate under this much contention
            } finally {
                finished.countDown();
            }
        };
    }
}