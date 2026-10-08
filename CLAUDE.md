# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Общение с пользователем ведётся на русском языке. Комментарии в коде (включая
Javadoc) тоже пишутся по-русски. Идентификаторы и строки API — английские.

## What this is

A Telegram bot that watches the Claude Code five-hour and weekly rate-limit
windows for several Claude accounts (one bot, one live status message per user's
private chat). Registration is closed: the first `/register` becomes ADMIN, later
ones are PENDING requests the admin approves via inline buttons; approved users
connect their account with `/token <refresh token>`. It runs as Docker containers
(app + PostgreSQL) on a VPS, deployed by GitHub Actions. See `README.md` for setup
and env vars.

Usage data comes from Anthropic's undocumented endpoint
`GET https://api.anthropic.com/api/oauth/usage` (headers: `Authorization: Bearer`,
`anthropic-beta: oauth-2025-04-20`, `User-Agent: claude-code/<version>`). Because
those are server-side numbers, the container needs no access to local
`~/.claude/projects/*.jsonl` logs. Two constraints that are easy to trip over:
the endpoint's TTL is 180 s (polling faster invites rate limits), and it answers
`403 Request not allowed` from geo-blocked regions — hence the optional
`HTTPS_PROXY` support in `HttpConfig`.

Token refresh goes to `POST https://api.anthropic.com/v1/oauth/token`
(`grant_type=refresh_token`, `client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e`).
Do not "fix" that host: the `claude.ai` and `console.anthropic.com` variants that
circulate in other projects answer `429 rate_limit_error` to every request,
including one carrying a deliberately invalid token, so a 429 there means wrong
host rather than throttling. Access tokens live 8 h and the refresh token is
rotated on every exchange, which is why each user needs a dedicated `claude` login
(`CLAUDE_CONFIG_DIR=~/.claude-monitor claude login`, then `refreshToken` from that
dir's `.credentials.json`) rather than a copy of their main CLI credentials. Tokens
from `claude setup-token` do not work: they only carry scope `user:inference`, and
`/api/oauth/usage` answers `403 OAuth token does not meet scope requirement user:profile`.

## Stack

- Spring Boot 4.1.1 (parent POM), Java 17, Maven via the bundled wrapper
- Base package: `com.example.claudeusagemonitor`
- Artifact: `com.example:claude-usage-monitor:0.0.1-SNAPSHOT`
- No web server. The app stays alive on the non-daemon Telegram polling thread
  in `BotPoller`.
- PostgreSQL via `spring-boot-starter-jdbc` + `JdbcClient` (no JPA), schema by
  Flyway (`spring-boot-starter-flyway` + `flyway-database-postgresql`,
  `src/main/resources/db/migration`). Besides that only `spring-boot-starter` +
  `spring-boot-starter-json`. HTTP is `java.net.http.HttpClient`; both the
  Anthropic and Telegram clients are hand-rolled, so there is no Telegram bot
  library to look for.
- OAuth tokens in the DB are encrypted with AES-256-GCM (`javax.crypto`, no extra
  dependency), key from `TOKEN_ENCRYPTION_KEY` (`monitor.token-encryption-key`,
  base64 of exactly 32 bytes, no default — the app refuses to start without it).
  Stored as `v1:` + base64(IV ‖ ciphertext ‖ tag), AAD = `chat_id`. Changing the
  key makes every stored token unreadable (users must resend `/token`). Tests use
  the fixed `TestBeans.TOKEN_KEY`.
- Tests use Testcontainers 2.x (`org.testcontainers.postgresql.PostgreSQLContainer`,
  `@ServiceConnection`) — Docker must be running for `./mvnw test`. Spring tests
  share one context via `testsupport/IntegrationTest`; Telegram, token exchange and
  the usage API are replaced by fakes in `testsupport/TestBeans`, nothing touches
  the network.
- **Spring Boot 4 ships Jackson 3**: imports are `tools.jackson.databind.*`, not
  `com.fasterxml.jackson.databind.*`. `JsonNode.asText()` is now `asString()`, and
  Jackson exceptions are unchecked (`tools.jackson.core.JacksonException`).

## Layout

- `account/` — `AccountRepository` (table `account`; the only place tokens are
  encrypted/decrypted via `TokenCipher` — an undecryptable row yields null tokens
  plus `Account.tokensUnreadable()` instead of an exception), `AccountSession` (per-account
  runtime state: tokens, last snapshot, window states, failure counter, board; all
  locks are per account) and the `AccountSessions` registry, `RegistrationService`
  (`/register`, approve/reject callbacks, `/users`, `/revoke`; a partial unique
  index guarantees a single ADMIN), `AccountService` (`/token`,
  `/forget`), `OwnerImporter` (one-shot import of the legacy `TELEGRAM_CHAT_ID` +
  `ANTHROPIC_*` + `/data/*.json` owner as USER/APPROVED, marked in `legacy_import`)
- `usage/` — `UsageClient` (the Anthropic endpoint), `TokenProvider` (refresh with
  rotation persisted to the DB, per-account backoff)
- `telegram/` — `TelegramClient` (raw Bot API, returns `TelegramResult`),
  `BotPoller` (long polling, `message` + `callback_query`), `CommandService`
  (parsing and access control), `StatusBoard` (status/alert messages; recreates the
  status only on "message to edit not found", honours 429 `retry_after`, pauses on
  "bot was blocked"), `MessageFormatter`
- `alert/AlertService` — scheduled poll over all monitored accounts (APPROVED +
  readable token), render every 15 s, threshold state reset when `resets_at`
  changes. Scheduler pool size is 2 so a slow poll does not stall rendering

## Commands

Use the Maven wrapper (`.\mvnw.cmd` on this Windows machine; `./mvnw` from the
Bash tool) rather than a system `mvn`.

```powershell
.\mvnw.cmd spring-boot:run              # run the app
.\mvnw.cmd test                         # all tests
.\mvnw.cmd test -Dtest=ClassName#method # single test class / method
.\mvnw.cmd package                      # build the executable jar into target/
.\mvnw.cmd spring-boot:build-image      # OCI image
```

Running the packaged jar: `java -jar target/claude-usage-monitor-0.0.1-SNAPSHOT.jar`
(needs PostgreSQL; defaults to `jdbc:postgresql://localhost:5432/monitor`, user and
password `monitor`, overridable via `SPRING_DATASOURCE_*`).

Docker (config comes from `.env`, which is gitignored — copy `.env.example`;
`POSTGRES_PASSWORD` is required, compose starts `db` before `monitor`):

```powershell
docker compose up -d --build
docker compose logs -f
```

There is no linter or formatter configured.
