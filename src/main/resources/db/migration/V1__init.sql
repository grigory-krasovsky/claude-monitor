-- Аккаунт Claude, привязанный к пользователю Telegram. Бот работает только в личке,
-- поэтому chat_id совпадает с Telegram user id и однозначно задаёт владельца.
create table account
(
    id                 bigserial primary key,
    chat_id            bigint      not null unique,
    username           text,
    display_name       text,
    role               text        not null check (role in ('ADMIN', 'USER')),
    status             text        not null check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    -- OAuth-токены Anthropic. Refresh ротируется при каждом обмене, поэтому база —
    -- единственное место, где лежит его актуальное значение.
    access_token       text,
    refresh_token      text,
    expires_at         timestamptz,
    -- Сообщения бота в чате аккаунта: живой статус и, при необходимости, алерт
    status_message_id  bigint,
    alert_message_id   bigint,
    alert_kind         text,
    -- Сообщение с заявкой у администратора: после решения с него снимаются кнопки
    request_message_id bigint,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

-- Администратор ровно один. Индекс по константе с условием допускает не больше одной
-- строки с ролью ADMIN — так база сама разрешает гонку двух «первых» /register:
-- второй получает нарушение уникальности и уходит по обычной ветке заявки.
create unique index account_single_admin on account ((true)) where role = 'ADMIN';

-- Какие chat_id уже импортированы из прежней однопользовательской конфигурации
-- (TELEGRAM_CHAT_ID, ANTHROPIC_*, /data/*.json). Без отметки /forget не был бы окончательным:
-- переменные окружения на сервере остаются, и следующий рестарт воскресил бы аккаунт
-- с давно погашенными токенами.
create table legacy_import
(
    chat_id     bigint primary key,
    imported_at timestamptz not null default now()
);
