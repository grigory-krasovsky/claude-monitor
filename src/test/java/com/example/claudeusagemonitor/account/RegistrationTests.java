package com.example.claudeusagemonitor.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.claudeusagemonitor.testsupport.DatabaseTest;
import com.example.claudeusagemonitor.testsupport.FakeTelegramClient;
import com.example.claudeusagemonitor.testsupport.FakeTelegramClient.Sent;
import com.example.claudeusagemonitor.testsupport.Updates;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Сценарии /register и решения по заявкам через CommandService, с фейковым Telegram. */
class RegistrationTests extends DatabaseTest {

    private static final long ADMIN = 100;
    private static final long USER = 200;
    private static final long STRANGER = 300;

    @Autowired
    private RegistrationService registration;

    @Test
    @DisplayName("первый /register делает администратором")
    void firstBecomesAdmin() {
        send(ADMIN, "/register");

        Account admin = repository.findByChatId(ADMIN).orElseThrow();
        assertThat(admin.role()).isEqualTo(AccountRole.ADMIN);
        assertThat(admin.status()).isEqualTo(AccountStatus.APPROVED);
        assertThat(admin.username()).isEqualTo("user100");
        assertThat(telegram.lastTextTo(ADMIN)).contains("администратор").contains("/token");
        assertThat(telegram.adminCommandChats).containsExactly(ADMIN);
    }

    @Test
    @DisplayName("второй /register — заявка PENDING и сообщение админу с кнопками")
    void secondBecomesPending() {
        send(ADMIN, "/register");
        telegram.reset();

        send(USER, "/register");

        Account pending = repository.findByChatId(USER).orElseThrow();
        assertThat(pending.status()).isEqualTo(AccountStatus.PENDING);
        assertThat(pending.role()).isEqualTo(AccountRole.USER);
        assertThat(pending.displayName()).isEqualTo("User 200");

        List<Sent> toAdmin = telegram.sentTo(ADMIN);
        assertThat(toAdmin).hasSize(1);
        assertThat(toAdmin.get(0).text()).contains("Заявка").contains("@user200").contains("200");
        assertThat(toAdmin.get(0).buttons()).containsValues("approve:200", "reject:200");
        assertThat(pending.requestMessageId()).isEqualTo(toAdmin.get(0).messageId());
        assertThat(telegram.lastTextTo(USER)).contains("Заявка отправлена");
    }

    @Test
    @DisplayName("кнопку заявки может нажать только администратор, повторное нажатие ничего не меняет")
    void onlyAdminDecides() {
        send(ADMIN, "/register");
        send(USER, "/register");
        long requestId = repository.findByChatId(USER).orElseThrow().requestMessageId();
        telegram.reset();

        commands.handle(Updates.callback(STRANGER, "approve:200", STRANGER, requestId));

        assertThat(repository.findByChatId(USER).orElseThrow().status()).isEqualTo(AccountStatus.PENDING);
        assertThat(telegram.answers).singleElement().satisfies(a -> assertThat(a.text()).contains("только администратор"));
        assertThat(telegram.edits).isEmpty();
        assertThat(telegram.sent).isEmpty();

        telegram.reset();
        commands.handle(Updates.callback(ADMIN, "approve:200", ADMIN, requestId));

        assertThat(repository.findByChatId(USER).orElseThrow().status()).isEqualTo(AccountStatus.APPROVED);
        assertThat(telegram.answers).singleElement().satisfies(a -> assertThat(a.text()).isEqualTo("Одобрено"));
        assertThat(telegram.edits).singleElement().satisfies(e -> {
            assertThat(e.messageId()).isEqualTo(requestId);
            assertThat(e.text()).contains("✅ одобрено");
        });
        assertThat(telegram.lastTextTo(USER)).contains("одобрена").contains("/token");

        telegram.reset();
        commands.handle(Updates.callback(ADMIN, "reject:200", ADMIN, requestId));

        assertThat(repository.findByChatId(USER).orElseThrow().status()).isEqualTo(AccountStatus.APPROVED);
        assertThat(telegram.answers).singleElement().satisfies(a -> assertThat(a.text()).contains("уже обработана"));
        assertThat(telegram.sentTo(USER)).isEmpty();
    }

    @Test
    @DisplayName("отклонение: заявитель узнаёт об этом, повторный /register админа не беспокоит")
    void rejectedDoesNotBotherAdmin() {
        send(ADMIN, "/register");
        send(USER, "/register");
        long requestId = repository.findByChatId(USER).orElseThrow().requestMessageId();
        commands.handle(Updates.callback(ADMIN, "reject:200", ADMIN, requestId));
        assertThat(telegram.lastTextTo(USER)).contains("отклонена");
        telegram.reset();

        send(USER, "/register");

        assertThat(telegram.lastTextTo(USER)).contains("отклонена");
        assertThat(telegram.sentTo(ADMIN)).isEmpty();
        assertThat(repository.findByChatId(USER).orElseThrow().status()).isEqualTo(AccountStatus.REJECTED);
    }

    @Test
    @DisplayName("повторный /register: на рассмотрении — админу второй раз не пишем; одобрен — «уже подключены»")
    void repeatedRegister() {
        send(ADMIN, "/register");
        send(USER, "/register");
        telegram.reset();

        send(USER, "/register");
        assertThat(telegram.lastTextTo(USER)).contains("уже на рассмотрении");
        assertThat(telegram.sentTo(ADMIN)).isEmpty();

        repository.decide(USER, AccountStatus.APPROVED);
        send(USER, "/register");
        assertThat(telegram.lastTextTo(USER)).contains("уже подключены");

        send(ADMIN, "/register");
        assertThat(telegram.lastTextTo(ADMIN)).contains("администратор");
        assertThat(repository.findAll()).filteredOn(Account::isAdmin).hasSize(1);
    }

    @Test
    @DisplayName("в группе регистрация не работает")
    void groupRegisterRefused() {
        commands.handle(Updates.message(-500, "group", USER, "/register"));

        assertThat(repository.findAll()).isEmpty();
        assertThat(telegram.lastTextTo(-500)).contains("личных сообщениях");
    }

    @Test
    @DisplayName("два одновременных /register при пустой базе: один админ, второй — заявка")
    void concurrentFirstRegister() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> a = pool.submit(() -> {
            start.await();
            registration.register(new RegistrationService.Applicant(1, "a", "A"));
            return null;
        });
        Future<?> b = pool.submit(() -> {
            start.await();
            registration.register(new RegistrationService.Applicant(2, "b", "B"));
            return null;
        });
        start.countDown();
        a.get();
        b.get();
        pool.shutdown();

        List<Account> all = repository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(Account::isAdmin).hasSize(1);
        assertThat(all).filteredOn(acc -> acc.status() == AccountStatus.PENDING).hasSize(1);
    }

    @Test
    @DisplayName("неподтверждённые получают только короткий отказ, без данных")
    void unapprovedAreDenied() {
        send(ADMIN, "/register");
        send(USER, "/register");
        telegram.reset();

        for (String command : List.of("/status", "/token", "/forget", "/users", "/revoke 100")) {
            send(USER, command);
            send(STRANGER, command);
        }

        assertThat(telegram.sent).isNotEmpty().allSatisfy(s -> {
            assertThat(s.text()).startsWith("⛔").contains("/register");
            assertThat(s.text()).doesNotContain("100", "user100", "токены");
        });
        assertThat(telegram.sentTo(ADMIN)).isEmpty();

        telegram.reset();
        send(STRANGER, "/token sk-ant-ort01-secret");

        // Токен удалён из чата даже при отказе и никуда не ушёл
        assertThat(telegram.deletes).hasSize(1);
        assertThat(tokenProvider.exchanged).isEmpty();
        assertThat(telegram.lastTextTo(STRANGER)).startsWith("⛔");
    }

    @Test
    @DisplayName("админские команды недоступны обычному пользователю")
    void adminCommandsForAdminOnly() {
        send(ADMIN, "/register");
        send(USER, "/register");
        repository.decide(USER, AccountStatus.APPROVED);
        telegram.reset();

        send(USER, "/users");
        assertThat(telegram.lastTextTo(USER)).contains("только для администратора");

        send(ADMIN, "/users");
        assertThat(telegram.lastTextTo(ADMIN)).contains("@user100").contains("@user200").contains("токен: нет");
    }

    @Test
    @DisplayName("заявка, не дошедшая до админа (429), отправляется повторным /register")
    void undeliveredRequestIsResent() {
        send(ADMIN, "/register");
        telegram.reset();
        telegram.sendResults.add(FakeTelegramClient.error(429, "Too Many Requests: retry after 5", 5));

        send(USER, "/register");
        assertThat(repository.findByChatId(USER).orElseThrow().requestMessageId()).isNull();

        send(USER, "/register");
        List<Sent> toAdmin = telegram.sentTo(ADMIN);
        assertThat(toAdmin).hasSize(2);
        assertThat(toAdmin.get(1).buttons()).containsValues("approve:200", "reject:200");
        assertThat(repository.findByChatId(USER).orElseThrow().requestMessageId()).isEqualTo(toAdmin.get(1).messageId());
        assertThat(telegram.lastTextTo(USER)).contains("Заявка отправлена");

        // Дошедшую заявку третий /register уже не дублирует
        send(USER, "/register");
        assertThat(telegram.sentTo(ADMIN)).hasSize(2);
        assertThat(telegram.lastTextTo(USER)).contains("уже на рассмотрении");
    }

    @Test
    @DisplayName("импортированный владелец не мешает: первый /register постороннего делает его администратором")
    void strangerFirstBecomesAdminDespiteImportedOwner() {
        repository.markLegacyImported(ADMIN);
        repository.importApproved(ADMIN, "access", "refresh", null, null, null, null);

        send(STRANGER, "/register");

        Account stranger = repository.findByChatId(STRANGER).orElseThrow();
        assertThat(stranger.role()).isEqualTo(AccountRole.ADMIN);
        assertThat(stranger.status()).isEqualTo(AccountStatus.APPROVED);
        assertThat(telegram.lastTextTo(STRANGER)).contains("администратор");
        assertThat(telegram.adminCommandChats).containsExactly(STRANGER);
        // Импортированная запись не тронута
        Account owner = repository.findByChatId(ADMIN).orElseThrow();
        assertThat(owner.role()).isEqualTo(AccountRole.USER);
        assertThat(owner.status()).isEqualTo(AccountStatus.APPROVED);
        assertThat(owner.refreshToken()).isEqualTo("refresh");
    }

    @Test
    @DisplayName("импортированный владелец делает /register, когда админ уже есть, — «уже подключены», роль не меняется")
    void importedOwnerAfterAdminStaysUser() {
        repository.markLegacyImported(ADMIN);
        repository.importApproved(ADMIN, "access", "refresh", null, null, null, null);
        send(STRANGER, "/register");
        telegram.reset();

        send(ADMIN, "/register");

        Account owner = repository.findByChatId(ADMIN).orElseThrow();
        assertThat(owner.role()).isEqualTo(AccountRole.USER);
        assertThat(owner.status()).isEqualTo(AccountStatus.APPROVED);
        assertThat(owner.refreshToken()).isEqualTo("refresh");
        assertThat(owner.requestMessageId()).isNull();
        assertThat(telegram.lastTextTo(ADMIN)).contains("уже подключены").doesNotContain("администратор");
        // Заявка админу не уходит, админ по-прежнему один
        assertThat(telegram.sentTo(STRANGER)).isEmpty();
        assertThat(repository.findAdmin()).hasValueSatisfying(a -> assertThat(a.chatId()).isEqualTo(STRANGER));
        assertThat(telegram.adminCommandChats).isEmpty();
    }
}
