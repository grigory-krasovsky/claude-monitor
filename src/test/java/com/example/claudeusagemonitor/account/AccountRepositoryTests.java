package com.example.claudeusagemonitor.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.claudeusagemonitor.telegram.StatusBoard;
import com.example.claudeusagemonitor.testsupport.DatabaseTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** Схема Flyway и репозиторий на настоящем PostgreSQL. */
class AccountRepositoryTests extends DatabaseTest {

    @Test
    @DisplayName("миграция создаёт таблицы, запись читается обратно со всеми полями")
    void roundTrip() {
        Instant expires = Instant.now().plus(8, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        assertThat(repository.importApproved(10, "acc", "ref", expires, 5L, 6L, StatusBoard.AlertKind.FAILURE))
                .isTrue();

        Account account = repository.findByChatId(10).orElseThrow();

        assertThat(account.role()).isEqualTo(AccountRole.USER);
        assertThat(account.status()).isEqualTo(AccountStatus.APPROVED);
        assertThat(account.accessToken()).isEqualTo("acc");
        assertThat(account.refreshToken()).isEqualTo("ref");
        assertThat(account.expiresAt()).isEqualTo(expires);
        assertThat(account.statusMessageId()).isEqualTo(5L);
        assertThat(account.alertMessageId()).isEqualTo(6L);
        assertThat(account.alertKind()).isEqualTo(StatusBoard.AlertKind.FAILURE);
        assertThat(account.isMonitored()).isTrue();
        assertThat(repository.findMonitored()).extracting(Account::chatId).containsExactly(10L);
        // Токены не должны утекать в логи через toString
        assertThat(account.toString()).doesNotContain("acc", "ref");
    }

    @Test
    @DisplayName("индекс допускает только одного администратора")
    void singleAdminIndex() {
        repository.promoteToAdmin(1, "first", "First");

        assertThatThrownBy(() -> repository.promoteToAdmin(2, "second", "Second"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(repository.findAll()).filteredOn(Account::isAdmin).extracting(Account::chatId)
                .containsExactly(1L);
        // Проигравший не оставил после себя записи — ON CONFLICT не сработал, строка не вставлена
        assertThat(repository.findByChatId(2)).isEmpty();
    }

    @Test
    @DisplayName("два одновременных «первых» — ровно один администратор")
    void concurrentPromotion() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.sql("delete from account").update();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Boolean>> results = new ArrayList<>();
            for (long chatId : new long[] {101, 102}) {
                Callable<Boolean> task = () -> {
                    start.await();
                    try {
                        repository.promoteToAdmin(chatId, null, null);
                        return true;
                    } catch (DuplicateKeyException e) {
                        return false;
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get() ? 1 : 0;
            }
            pool.shutdown();

            assertThat(winners).isEqualTo(1);
            assertThat(repository.findAll()).filteredOn(Account::isAdmin).hasSize(1);
        }
    }

    @Test
    @DisplayName("повышение до админа сохраняет токены и сообщения существующей записи")
    void promotionKeepsTokens() {
        repository.importApproved(7, "acc", "ref", null, 99L, null, null);

        Account admin = repository.promoteToAdmin(7, "owner", "Owner");

        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.refreshToken()).isEqualTo("ref");
        assertThat(admin.statusMessageId()).isEqualTo(99L);
        assertThat(admin.username()).isEqualTo("owner");
    }

    @Test
    @DisplayName("решение по заявке применяется один раз и только к PENDING")
    void decideIsIdempotent() {
        assertThat(repository.createPending(5, "u", "U")).isTrue();
        assertThat(repository.createPending(5, "u", "U")).isFalse();

        assertThat(repository.decide(5, AccountStatus.APPROVED)).isPresent();
        assertThat(repository.decide(5, AccountStatus.REJECTED)).isEmpty();
        assertThat(repository.findByChatId(5).orElseThrow().status()).isEqualTo(AccountStatus.APPROVED);
    }
}
