# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Общение с пользователем ведётся на русском языке. Комментарии в коде (включая
Javadoc) тоже пишутся по-русски. Идентификаторы и строки API — английские.

## What this is

A Telegram bot that watches the Claude Code five-hour rate-limit window and
alerts on utilization thresholds. It runs as a Docker container on a VPS,
deployed by GitHub Actions. See `README.md` for setup and env vars.

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
rotated on every exchange, which is why a VPS needs its own `claude` login
(`CLAUDE_CONFIG_DIR`) rather than a copy of the developer's credentials.

## Stack

- Spring Boot 4.1.1 (parent POM), Java 17, Maven via the bundled wrapper
- Base package: `com.example.claudeusagemonitor`
- Artifact: `com.example:claude-usage-monitor:0.0.1-SNAPSHOT`
- No web server, no database. The app stays alive on the non-daemon Telegram
  polling thread in `BotPoller`.
- Dependencies are only `spring-boot-starter` + `spring-boot-starter-json`.
  HTTP is `java.net.http.HttpClient`; both the Anthropic and Telegram clients are
  hand-rolled, so there is no Telegram bot library to look for.
- **Spring Boot 4 ships Jackson 3**: imports are `tools.jackson.databind.*`, not
  `com.fasterxml.jackson.databind.*`. `JsonNode.asText()` is now `asString()`, and
  Jackson exceptions are unchecked (`tools.jackson.core.JacksonException`).

## Layout

- `usage/` — `UsageClient` (the Anthropic endpoint), `TokenProvider` (OAuth token,
  optional refresh with rotation persisted to `/data/tokens.json`)
- `telegram/` — `TelegramClient` (raw Bot API), `BotPoller` (long polling),
  `CommandService` (`/status`, `/chatid`, `/help`), `MessageFormatter`
- `alert/AlertService` — scheduled poll, one alert per threshold per window,
  state reset when `resets_at` changes

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

Docker (config comes from `.env`, which is gitignored — copy `.env.example`):

```powershell
docker compose up -d --build
docker compose logs -f
```

There is no linter or formatter configured.
