@echo off
rem Launcher: cmd mangles UTF-8 Cyrillic, so the logic below runs in PowerShell.
set "SELF=%~f0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s = [IO.File]::ReadAllText($env:SELF, [Text.Encoding]::UTF8); Invoke-Expression $s.Substring($s.IndexOf('#' + 'PS-BEGIN'))"
exit /b %errorlevel%

#PS-BEGIN
# Получает refresh token Claude для бота claude-usage-monitor.
#
# Логин идёт во временный отдельный каталог конфигурации, а не в %USERPROFILE%\.claude:
# Anthropic ротирует refresh token при каждом обмене, поэтому токен, общий с основным
# Claude Code, выбивали бы друг у друга бот и CLI. После выдачи каталог удаляется без
# logout — logout отозвал бы токен на сервере, и бот остался бы ни с чем.
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Finish([int] $code) {
    Write-Host ''
    Read-Host 'Нажмите Enter, чтобы закрыть окно' | Out-Null
    exit $code
}

if (-not (Get-Command claude -ErrorAction SilentlyContinue)) {
    Write-Host 'Claude Code не найден в PATH. Установите его: https://claude.com/claude-code'
    Finish 1
}

# Возвращает код выхода: 0 — токен выдан, 1 — нет
function Get-Token([string] $dir) {
    Write-Host ''
    Write-Host 'Браузер сам не откроется: ссылка для входа сейчас окажется в буфере обмена'
    Write-Host '(и будет напечатана ниже после «If the browser didn''t open, visit:»).'
    Write-Host '1. Вставьте её (Ctrl+V) в адресную строку браузера и войдите в аккаунт Claude'
    Write-Host '   (подписка Pro/Max). Надпись «Opening browser…» ниже не обращайте внимания.'
    Write-Host '2. Если браузер на этом же компьютере, логин завершится сам. Если покажет код —'
    Write-Host '   вставьте его сюда после «Paste code here if prompted» и нажмите Enter.'
    Write-Host '   Ctrl+C в этом окне не нажимайте — он прервёт логин.'
    Write-Host 'Ваш обычный Claude Code это не затронет.'
    Write-Host ''
    # Out-Host: вывод CLI идёт на экран, а не в возвращаемое значение функции
    & claude auth login --claudeai | Out-Host

    $creds = Join-Path $dir '.credentials.json'
    if (-not (Test-Path -LiteralPath $creds)) {
        Write-Host ''
        Write-Host 'Логин не завершён: файл с токенами не появился.'
        Write-Host 'Если выше «status code 403» — это блокировка региона: нужен прокси или VPN,'
        Write-Host 'через который работает ваш обычный Claude Code.'
        return 1
    }
    $oauth = (Get-Content -Raw -LiteralPath $creds | ConvertFrom-Json).claudeAiOauth
    if (-not $oauth.refreshToken -or -not $oauth.refreshToken.StartsWith('sk-ant-ort01-')) {
        Write-Host 'В ответе нет refresh token. Попробуйте ещё раз.'
        return 1
    }
    # Без user:profile эндпоинт лимитов отвечает 403 — такой токен боту бесполезен
    if ($oauth.scopes -notcontains 'user:profile') {
        Write-Host 'У токена нет scope user:profile — бот не сможет читать лимиты.'
        return 1
    }

    Set-Clipboard -Value ('/token ' + $oauth.refreshToken)
    Write-Host ''
    Write-Host 'Готово. В буфере обмена команда вида «/token sk-ant-ort01-...».'
    Write-Host '1. Откройте личный чат с ботом в Telegram.'
    Write-Host '2. Вставьте (Ctrl+V) и отправьте — бот сам удалит это сообщение.'
    Write-Host '3. Вернитесь сюда и нажмите Enter: буфер обмена будет очищен.'
    Read-Host | Out-Null

    # Запись в истории Win+V, если она включена, этим не стирается
    Set-Clipboard -Value ' '
    Write-Host 'Буфер обмена очищен. Если включена история буфера (Win+V), удалите запись с токеном и там.'
    return 0
}

# Прокси для Claude Code часто прописан не в системе, а в env основного settings.json.
# Временный каталог его не видит, и обмен кода на токен идёт напрямую — из
# заблокированного региона это 403. Переносим только сетевые переменные и не
# перетираем уже заданные в окружении.
$mainDir = if ($env:CLAUDE_CONFIG_DIR) { $env:CLAUDE_CONFIG_DIR } else { Join-Path $env:USERPROFILE '.claude' }
$settings = Join-Path $mainDir 'settings.json'
if (Test-Path -LiteralPath $settings) {
    try {
        $envBlock = (Get-Content -Raw -LiteralPath $settings | ConvertFrom-Json).env
        foreach ($name in 'HTTPS_PROXY', 'HTTP_PROXY', 'NO_PROXY', 'NODE_EXTRA_CA_CERTS') {
            $value = $envBlock.$name
            if ($value -and -not [Environment]::GetEnvironmentVariable($name)) {
                [Environment]::SetEnvironmentVariable($name, [string] $value)
                Write-Host "Взят $name из $settings"
            }
        }
    } catch {
        Write-Host "Не удалось прочитать $settings — продолжаю без него."
    }
}

$dir = Join-Path $env:TEMP ('claude-monitor-login-' + [guid]::NewGuid().ToString('N'))
$env:CLAUDE_CONFIG_DIR = $dir
# Claude Code открывает ссылку командой из BROWSER. Подставляем свою: браузер не
# запускается, а ссылка уходит в буфер обмена — её открывают вручную, в нужном браузере.
# set "URL=..." в кавычках: в ссылке есть &, которые cmd иначе принял бы за разделитель команд
$noBrowser = Join-Path $env:TEMP ('claude-monitor-nobrowser-' + [guid]::NewGuid().ToString('N') + '.cmd')
Set-Content -LiteralPath $noBrowser -Encoding ASCII -Value @(
    '@echo off',
    'set "URL=%~1"',
    'powershell -NoProfile -Command "Set-Clipboard -Value $env:URL"',
    'exit /b 0'
)
$env:BROWSER = $noBrowser
$code = 1
try {
    $code = Get-Token $dir
}
finally {
    # Каталог удаляем при любом исходе, в том числе при отменённом логине
    Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item Env:CLAUDE_CONFIG_DIR -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $noBrowser -Force -ErrorAction SilentlyContinue
    Remove-Item Env:BROWSER -ErrorAction SilentlyContinue
}
Write-Host 'Временные файлы удалены.'
Finish $code
