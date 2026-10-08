package com.example.claudeusagemonitor.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.claudeusagemonitor.alert.AlertService;
import com.example.claudeusagemonitor.testsupport.DatabaseTest;
import com.example.claudeusagemonitor.testsupport.FakeTelegramClient;
import com.example.claudeusagemonitor.testsupport.FakeTelegramClient.Delete;
import com.example.claudeusagemonitor.testsupport.Updates;
import com.example.claudeusagemonitor.usage.TokenProvider.TokenException;
import com.example.claudeusagemonitor.usage.UsageClient.UsageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** /token, /status, /forget и /revoke на базе и фейках сети. */
class AccountLifecycleTests extends DatabaseTest {

    private static final long ADMIN = 100;
    private static final long USER = 200;
    private static final long GROUP = -500;

    @Autowired
    private AlertService alertService;

    @BeforeEach
    void approveUser() {
        send(ADMIN, "/register");
        send(USER, "/register");
        repository.decide(USER, AccountStatus.APPROVED);
        telegram.reset();
    }

    @Test
    @DisplayName("/token: сообщение удалено, токены сохранены, мониторинг запущен сразу")
    void tokenConnects() {
        var update = Updates.privateMessage(USER, "/token sk-ant-ort01-abc");
        commands.handle(update);

        assertThat(telegram.deletes).contains(new Delete(USER, Updates.messageId(update)));
        assertThat(tokenProvider.exchanged).containsExactly("sk-ant-ort01-abc");

        Account account = repository.findByChatId(USER).orElseThrow();
        assertThat(account.accessToken()).isEqualTo("access-sk-ant-ort01-abc");
        assertThat(account.refreshToken()).isEqualTo("rotated-sk-ant-ort01-abc");
        assertThat(account.isMonitored()).isTrue();
        assertThat(sessions.find(USER)).isPresent();

        // «Проверяю…» переписано в «подключено», а статус встал последним сообщением
        assertThat(telegram.edits).anySatisfy(e -> assertThat(e.text()).contains("подключён"));
        assertThat(telegram.lastTextTo(USER)).contains("токены 42%");
        assertThat(account.statusMessageId()).isEqualTo(telegram.sentTo(USER).get(telegram.sentTo(USER).size() - 1).messageId());
    }

    @Test
    @DisplayName("токен, присланный без команды, тоже удаляется и подключается")
    void bareTokenIsHandled() {
        var update = Updates.privateMessage(USER, "sk-ant-ort01-bare");
        commands.handle(update);

        assertThat(telegram.deletes).contains(new Delete(USER, Updates.messageId(update)));
        assertThat(tokenProvider.exchanged).containsExactly("sk-ant-ort01-bare");
    }

    @Test
    @DisplayName("/token: понятные ошибки — недействительный токен, нет scope, гео-блок")
    void tokenErrors() {
        tokenProvider.behaviour = token -> {
            throw new TokenException(400, "{\"error\":\"invalid_grant\"}");
        };
        send(USER, "/token sk-ant-ort01-dead");
        assertThat(telegram.edits.get(telegram.edits.size() - 1).text()).contains("недействителен");

        tokenProvider.behaviour = token -> new com.example.claudeusagemonitor.usage.TokenProvider.TokenGrant(
                "a", "r", null);
        usageClient.behaviour = token -> {
            throw new UsageException(403, "{\"type\":\"error\",\"error\":{\"type\":\"permission_error\","
                    + "\"message\":\"OAuth token does not meet scope requirement user:profile\"}}");
        };
        send(USER, "/token sk-ant-ort01-noscope");
        assertThat(telegram.edits.get(telegram.edits.size() - 1).text()).contains("user:profile");

        usageClient.behaviour = token -> {
            throw new UsageException(403, "Request not allowed");
        };
        send(USER, "/token sk-ant-ort01-geo");
        assertThat(telegram.edits.get(telegram.edits.size() - 1).text()).contains("по региону");

        send(USER, "/token sk-ant-oat01-access");
        assertThat(telegram.lastTextTo(USER)).contains("access token");

        Account account = repository.findByChatId(USER).orElseThrow();
        assertThat(account.hasToken()).isFalse();
        assertThat(sessions.find(USER)).isEmpty();
    }

    @Test
    @DisplayName("/stop и /resume больше не команды: одобренному — молчание, мониторинг идёт дальше")
    void stopAndResumeAreGone() {
        send(USER, "/token sk-ant-ort01-abc");
        long statusId = repository.findByChatId(USER).orElseThrow().statusMessageId();
        telegram.reset();

        send(USER, "/stop");
        send(USER, "/resume");

        assertThat(telegram.sent).isEmpty();
        assertThat(telegram.deletes).isEmpty();
        assertThat(sessions.find(USER)).isPresent();
        assertThat(repository.findByChatId(USER).orElseThrow().statusMessageId()).isEqualTo(statusId);
    }

    @Test
    @DisplayName("/status: сессии ещё нет, а токены есть — сессия поднимется ближайшим опросом")
    void statusBeforeSessionIsUp() {
        send(USER, "/token sk-ant-ort01-abc");
        sessions.detach(USER);
        telegram.reset();

        send(USER, "/status");
        assertThat(telegram.lastTextTo(USER)).contains("запускается");

        sessions.sync();
        assertThat(sessions.find(USER)).isPresent();
    }

    @Test
    @DisplayName("после /forget ни sync, ни запоздалый опрос старой сессии не возвращают аккаунт в чат")
    void forgetIsFinalForSyncAndLatePoll() {
        send(USER, "/token sk-ant-ort01-abc");
        send(ADMIN, "/token sk-ant-ort01-admin");
        AccountSession userSession = sessions.find(USER).orElseThrow();
        AccountSession adminSession = sessions.find(ADMIN).orElseThrow();

        send(USER, "/forget");
        send(ADMIN, "/forget");
        telegram.reset();

        sessions.sync();
        assertThat(sessions.find(USER)).isEmpty();
        assertThat(sessions.find(ADMIN)).isEmpty();

        // Опрос, начатый до /forget, заканчивается уже после него
        alertService.poll(userSession);
        alertService.poll(adminSession);
        assertThat(telegram.sent).isEmpty();
        assertThat(telegram.edits).isEmpty();
        assertThat(repository.findByChatId(USER)).isEmpty();
        Account admin = repository.findByChatId(ADMIN).orElseThrow();
        assertThat(admin.hasToken()).isFalse();
        assertThat(admin.statusMessageId()).isNull();
    }

    @Test
    @DisplayName("/forget: обычный пользователь удаляется целиком, админ сохраняет роль")
    void forget() {
        send(USER, "/token sk-ant-ort01-abc");
        send(ADMIN, "/token sk-ant-ort01-admin");

        send(USER, "/forget");
        assertThat(repository.findByChatId(USER)).isEmpty();
        assertThat(sessions.find(USER)).isEmpty();

        send(ADMIN, "/forget");
        Account admin = repository.findByChatId(ADMIN).orElseThrow();
        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.hasToken()).isFalse();
        assertThat(admin.statusMessageId()).isNull();
        assertThat(sessions.find(ADMIN)).isEmpty();
    }

    @Test
    @DisplayName("/revoke: пользователь отклонён, статус удалён; себя отозвать нельзя")
    void revoke() {
        send(USER, "/token sk-ant-ort01-abc");
        long statusId = repository.findByChatId(USER).orElseThrow().statusMessageId();
        telegram.reset();

        send(ADMIN, "/revoke " + ADMIN);
        assertThat(telegram.lastTextTo(ADMIN)).contains("самого себя");

        send(ADMIN, "/revoke " + USER);

        Account revoked = repository.findByChatId(USER).orElseThrow();
        assertThat(revoked.status()).isEqualTo(AccountStatus.REJECTED);
        assertThat(revoked.isMonitored()).isFalse();
        assertThat(telegram.deletes).contains(new Delete(USER, statusId));
        assertThat(sessions.find(USER)).isEmpty();

        telegram.reset();
        send(USER, "/status");
        assertThat(telegram.lastTextTo(USER)).startsWith("⛔");
    }

    @Test
    @DisplayName("проба лимитов упала временно (429) — токены сохранены, мониторинг запущен без данных")
    void transientProbeFailureKeepsGrant() {
        usageClient.behaviour = token -> {
            throw new UsageException(429, "{\"error\":\"rate_limited\"}");
        };
        send(USER, "/token sk-ant-ort01-abc");

        Account account = repository.findByChatId(USER).orElseThrow();
        assertThat(account.refreshToken()).isEqualTo("rotated-sk-ant-ort01-abc");
        assertThat(account.isMonitored()).isTrue();
        assertThat(sessions.find(USER)).isPresent();
        assertThat(telegram.edits).anySatisfy(e -> assertThat(e.text()).contains("подключён").contains("429"));
        assertThat(telegram.lastTextTo(USER)).contains("Данных пока нет");
        assertThat(account.statusMessageId()).isNotNull();
    }

    @Test
    @DisplayName("токен в кавычках или строкой из .credentials.json извлекается, сообщение удаляется")
    void wrappedTokenIsExtracted() {
        var update = Updates.privateMessage(USER, "\"refreshToken\": \"sk-ant-ort01-quoted_1\",");
        commands.handle(update);

        assertThat(telegram.deletes).contains(new Delete(USER, Updates.messageId(update)));
        assertThat(tokenProvider.exchanged).containsExactly("sk-ant-ort01-quoted_1");
    }

    @Test
    @DisplayName("токен, вписанный правкой сообщения, тоже удаляется; правки без токена не повторяют команды")
    void editedMessageWithToken() {
        var update = Updates.edited(Updates.privateMessage(USER, "sk-ant-ort01-edited"));
        commands.handle(update);

        assertThat(telegram.deletes).contains(new Delete(USER, Updates.messageId(update)));
        assertThat(tokenProvider.exchanged).containsExactly("sk-ant-ort01-edited");

        telegram.reset();
        commands.handle(Updates.edited(Updates.privateMessage(USER, "/help")));
        assertThat(telegram.sent).isEmpty();
    }

    @Test
    @DisplayName("не удалось удалить сообщение с токеном — бот прямо предупреждает, что токен виден")
    void undeletableTokenWarns() {
        telegram.deleteResults.add(FakeTelegramClient.error(400, "Bad Request: message can't be deleted", null));

        commands.handle(Updates.message(GROUP, "group", USER, "/token@bot sk-ant-ort01-group"));

        assertThat(telegram.sentTo(GROUP)).anySatisfy(s -> assertThat(s.text()).contains("Не удалось удалить"));
        assertThat(tokenProvider.exchanged).isEmpty();
    }

    @Test
    @DisplayName("алерт о сбое, пропущенный под паузой Telegram, поднимается следующим сбоем и не дублируется")
    void failureAlertSurvivesPause() {
        send(USER, "/token sk-ant-ort01-abc");
        AccountSession session = sessions.find(USER).orElseThrow();
        usageClient.behaviour = token -> {
            throw new UsageException(0, "сеть недоступна");
        };
        telegram.reset();

        session.board().pauseUntil(java.time.Instant.now().plusSeconds(60));
        alertService.poll(session);
        alertService.poll(session);
        assertThat(telegram.sent).isEmpty();

        session.board().pauseUntil(null);
        alertService.poll(session);
        assertThat(telegram.sentTo(USER)).anySatisfy(s -> assertThat(s.text()).contains("не может получить данные"));

        int sent = telegram.sent.size();
        alertService.poll(session);
        assertThat(telegram.sent).hasSize(sent);
    }
}
