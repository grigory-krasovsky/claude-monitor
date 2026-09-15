# Claude Usage Monitor

Телеграм-бот, который следит за пятичасовым лимитом Claude Code и присылает алерты
при пересечении порогов утилизации.

Данные берутся из недокументированного эндпоинта Anthropic
`GET https://api.anthropic.com/api/oauth/usage` (см.
[issue #202](https://github.com/Maciek-roboblog/Claude-Code-Usage-Monitor/issues/202)).
Это серверные цифры, поэтому монитору не нужен доступ к локальным jsonl-логам
Claude Code и учитывается работа со всех устройств — именно поэтому его можно
держать на VPS.

## Что умеет

- опрос лимитов раз в 3 минуты (TTL эндпоинта — 180 с, чаще ходить нельзя);
- алерты на порогах 50 / 75 / 90 / 95 % — каждый порог не более одного раза за окно;
- уведомление о сбросе пятичасового окна;
- команды `/status`, `/chatid`, `/help`.

## Быстрый старт

```bash
cp .env.example .env
# заполнить TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, ANTHROPIC_ACCESS_TOKEN
docker compose up -d --build
docker compose logs -f
```

## Где взять токен Anthropic

Рекомендуемый способ — отдельный долгоживущий токен (живёт около года),
не конфликтующий с локальной сессией Claude Code:

```bash
claude setup-token
```

Полученное значение `sk-ant-oat01-...` кладётся в `ANTHROPIC_ACCESS_TOKEN`.

Альтернатива — `refreshToken` из `~/.claude/.credentials.json` в переменной
`ANTHROPIC_REFRESH_TOKEN`: тогда монитор сам обновляет access token и сохраняет
ротацию в `/data/tokens.json`. Учтите, что Anthropic ротирует refresh token при
каждом обмене, так что вторая копия на VPS может разлогинить локальный Claude Code.

## Где взять chat id

Три способа, любой на выбор:

1. Написать боту `/chatid` — он ответит идентификатором текущего чата.
2. Открыть в браузере `https://api.telegram.org/bot<ТОКЕН>/getUpdates`, предварительно
   отправив боту любое сообщение (для группы — упомянув бота), и взять `message.chat.id`.
3. Переслать сообщение из нужного чата боту `@getidsbot` или `@userinfobot`.

Для групп id отрицательный (например `-1001234567890`) — знак минуса обязателен.

## Переменные окружения

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | — | токен от @BotFather |
| `TELEGRAM_CHAT_ID` | — | чат для алертов; без него работают только команды |
| `ANTHROPIC_ACCESS_TOKEN` | — | OAuth-токен для эндпоинта лимитов |
| `ANTHROPIC_REFRESH_TOKEN` | — | необязательный refresh token |
| `POLL_INTERVAL` | `3m` | интервал опроса, меньше 3m ставить нельзя |
| `THRESHOLDS` | `50,75,90,95` | пороги алертов в процентах |
| `TIMEZONE` | `Europe/Moscow` | часовой пояс для времени сброса |
| `NOTIFY_ON_START` | `true` | сообщение при запуске монитора |
| `NOTIFY_ON_RESET` | `true` | сообщение при сбросе пятичасового окна |
| `HTTPS_PROXY` | — | HTTP-прокси для исходящих запросов, если нужен |

### Про 403 от Anthropic

`api.anthropic.com` отдаёт `403 Request not allowed` для запросов из заблокированных
регионов. Если VPS стоит в таком регионе, задайте `HTTPS_PROXY` — Java, в отличие от
curl, эту переменную сама не читает, поэтому приложение прокидывает её в HTTP-клиент
явно.

## Деплой на VPS

Подготовка сервера (один раз):

```bash
mkdir -p /opt/claude-usage-monitor && cd /opt/claude-usage-monitor
curl -O https://raw.githubusercontent.com/OWNER/claude-usage-monitor/master/docker-compose.yml
nano .env   # содержимое .env.example, заполненное своими значениями
```

В `docker-compose.yml` на сервере строку `build: .` можно убрать — там образ только тянется.

Секреты репозитория для GitHub Actions:

| Секрет | Назначение |
|---|---|
| `VPS_HOST` | адрес сервера |
| `VPS_USER` | пользователь ssh |
| `VPS_SSH_KEY` | приватный ключ (формат OpenSSH) |
| `VPS_PORT` | порт ssh, если не 22 |
| `VPS_PATH` | каталог деплоя, если не `/opt/claude-usage-monitor` |

Пайплайн на push в `master`: сборка и тесты → публикация образа в GHCR →
ssh на сервер, `docker compose pull && docker compose up -d`.

## Локальная разработка

```bash
./mvnw test
./mvnw spring-boot:run
```
