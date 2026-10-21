-- Когда опубликовано статусное сообщение. Telegram не даёт боту удалить сообщение старше
-- 48 часов, поэтому статус пересоздаётся заранее; NULL у уже существующего статуса означает
-- «возраст неизвестен» — он пересоздаётся при первой же отрисовке.
alter table account
    add column status_posted_at timestamptz;

-- Сообщения чата, которые бот должен убрать позже: ответы на команды, уведомления,
-- а также то, что не удалось удалить сразу (429, сеть). Лежат в базе, чтобы очистка
-- пережила рестарт и редеплой. Внешнего ключа на account нет: отвечать приходится и тем,
-- у кого аккаунта ещё нет (/register, /help).
create table temporary_message
(
    chat_id    bigint      not null,
    message_id bigint      not null,
    delete_at  timestamptz not null,
    primary key (chat_id, message_id)
);

create index temporary_message_due on temporary_message (delete_at);
