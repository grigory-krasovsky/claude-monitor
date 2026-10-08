package com.example.claudeusagemonitor.account;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Реестр рантайм-сессий отслеживаемых аккаунтов, ключ — chat_id.
 *
 * <p>Сессия создаётся из строки базы один раз и дальше главнее её: токены ротируются
 * в памяти и уже оттуда пишутся в базу. Поэтому {@link #sync} только добавляет новые
 * и убирает выбывшие аккаунты, но не перечитывает существующие — иначе опрос мог бы
 * откатить токен, обновлённый секундой раньше.
 *
 * <p>Состав реестра меняется под блокировкой конкретного chat_id ({@link #locked}):
 * команда (/forget, /token) меняет базу и реестр вместе, а {@code sync} перепроверяет
 * строку под той же блокировкой. Без этого опрос со списком, прочитанным за миг до
 * /forget, вернул бы аккаунт в реестр, и бот снова нарисовал бы только что удалённый статус.
 */
@Component
public class AccountSessions {

    private final AccountRepository repository;
    private final ConcurrentMap<Long, AccountSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Object> locks = new ConcurrentHashMap<>();

    public AccountSessions(AccountRepository repository) {
        this.repository = repository;
    }

    /** Выполняет действие под блокировкой состава реестра для одного chat_id. */
    public <T> T locked(long chatId, Supplier<T> action) {
        synchronized (locks.computeIfAbsent(chatId, id -> new Object())) {
            return action.get();
        }
    }

    public void locked(long chatId, Runnable action) {
        locked(chatId, () -> {
            action.run();
            return null;
        });
    }

    /** Подключает сессию, если её ещё нет. */
    public AccountSession attach(Account account) {
        return sessions.computeIfAbsent(account.chatId(), id -> AccountSession.of(account));
    }

    /** Заменяет сессию свежей из базы — после /token, когда токены в базе новее памяти. */
    public AccountSession replace(Account account) {
        AccountSession fresh = AccountSession.of(account);
        AccountSession old = sessions.put(account.chatId(), fresh);
        if (old != null) {
            old.detach();
        }
        return fresh;
    }

    public Optional<AccountSession> find(long chatId) {
        return Optional.ofNullable(sessions.get(chatId));
    }

    /** Убирает сессию из реестра и помечает отцепленной. */
    public Optional<AccountSession> detach(long chatId) {
        AccountSession session = sessions.remove(chatId);
        if (session != null) {
            session.detach();
        }
        return Optional.ofNullable(session);
    }

    public Collection<AccountSession> all() {
        return List.copyOf(sessions.values());
    }

    /**
     * Приводит реестр к списку отслеживаемых аккаунтов из базы. Подстраховка на случай,
     * если состояние в базе поменяли в обход команд бота, и способ поднять сессии после старта.
     */
    public void sync() {
        Set<Long> monitored = repository.findMonitored().stream()
                .map(Account::chatId)
                .collect(Collectors.toSet());
        Set<Long> changed = new HashSet<>(monitored);
        changed.addAll(sessions.keySet());
        changed.removeIf(id -> monitored.contains(id) == sessions.containsKey(id));
        // Расхождений обычно нет, так что перепроверка строки под блокировкой почти бесплатна
        for (long chatId : changed) {
            locked(chatId, () -> {
                Optional<Account> current = repository.findByChatId(chatId).filter(Account::isMonitored);
                if (current.isPresent()) {
                    attach(current.get());
                } else {
                    detach(chatId);
                }
            });
        }
    }
}
