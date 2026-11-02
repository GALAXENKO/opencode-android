# OpenCode для Android

Полноценный порт [OpenCode](https://opencode.ai) на Android: **настоящий сервер OpenCode**
(официальный musl-бинарник CLI v2.2.5) запускается внутри APK, а его официальный веб-интерфейс
открывается в WebView. Это не заглушка и не урезанный мобильный UI — внутри работает тот же
движок, что и на десктопе: тот же конфиг, те же провайдеры, те же инструменты (shell, read/write,
grep, edit), та же сессия и та же история.

## Что работает

| Возможность | Статус |
|---|---|
| Сервер OpenCode v2.2.5 внутри APK (aarch64-musl) | ✅ |
| Официальный веб-UI v2 в WebView, cookie-авторизация | ✅ |
| Реальная генерация через любой провайдер | ✅ |
| Инструмент `shell` (toybox `/system/bin/sh`) | ✅ |
| Добавление своего провайдера в конфиг (как на десктопе) | ✅ |
| Редактор конфига + диалог «Add provider» | ✅ |
| Экспорт/скачивание файлов из UI (включая blob) в `Download` | ✅ |
| Загрузка файлов в чат (file chooser) | ✅ |
| Работа в фоне (foreground service + постоянный уведомление) | ✅ |
| Лог сервера: просмотр и отправка файла | ✅ |

Проверено на реальном устройстве: Samsung SM-S731B, Android 17 (SDK 37), arm64-v8a.

## Как это устроено

```
┌─────────────────────────── APK (ai.opencode.mobile) ───────────────────────────┐
│                                                                                │
│  MainActivity ── WebView ──► http://127.0.0.1:<port>/  (официальный веб-UI v2) │
│       │                              ▲                                         │
│       │ cookie opencode_session_<port>=<exp>.<hmac>                            │
│       ▼                              │                                         │
│  ServerService (foreground) ─────────┘                                         │
│       │ exec                                                                   │
│       ▼                                                                        │
│  ld-musl-aarch64.so --library-path … libopencode.so serve --port <port>         │
│       │                                                                        │
│       └─ cwd = $HOME (files/home), конфиг в $HOME/.config/opencode/            │
└────────────────────────────────────────────────────────────────────────────────┘
```

Ключевые моменты, без которых это не работало бы:

1. **Бинарник динамический.** У официального CLI `PT_INTERP = /lib/ld-musl-aarch64.so.1` и
   `DT_NEEDED = libstdc++.so.6, libc.musl-aarch64.so.1`. Android такого загрузчика не имеет,
   поэтому musl-загрузчик и C++-рантайм едут в APK, а процесс запускается **явно через
   загрузчик** с `--library-path` на распакованные `.so`.
2. **AGP пакует только `*.so`.** Версионированные имена (`libstdc++.so.6`, `ld-musl-aarch64.so.1`)
   молча игнорируются, поэтому в `jniLibs` они лежат под «упаковочными» именами
   (`libstdcxx.so`, `ld-musl-aarch64.so`), а `AppEnv.ensureRuntimeLibs()` на старте создаёт
   в `files/lib` симлинки с настоящими именами.
3. **Нет `/etc`.** Резолвер DNS внутри бинарника и musl-загрузчик читают `/etc/resolv.conf`
   и `/etc/hosts`, которых у приложения нет. Оба файла пропатчены на относительные пути
   (`tools/patch-binary.js`, `tools/patch-musl.js`) и генерируются из `ConnectivityManager`
   при каждом старте, а `cwd` процесса выставлен в `$HOME`.
4. **Авторизация сервера.** `serve` требует пароль. Приложение генерирует пароль, считает
   сессионную cookie по той же схеме, что и сервер
   (`HMAC-SHA256(password, "opencode-session-v1")` → `HMAC-SHA256(inner, exp)` → base64url)
   и подставляет её в `CookieManager` для `127.0.0.1:<port>`. Никаких запросов пароля в UI.

## Сборка

Нужно: JDK 17, Android SDK с `platforms;android-34` и `build-tools;34.0.0`, Node.js (для патча
бинарника), Gradle 8.7 (или Android Studio).

```powershell
# 1. Забрать и пропатчить нативный бинарник (~235 МБ, в git его нет)
powershell -ExecutionPolicy Bypass -File tools/build-native.ps1

# 2. Собрать APK
cd OpenCodeAndroid
gradle :app:assembleDebug
```

Готовый APK: `OpenCodeAndroid/app/build/outputs/apk/debug/app-debug.apk` (~130 МБ, arm64-v8a).

Установка на устройство:

```powershell
adb install -r -g OpenCodeAndroid\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n ai.opencode.mobile/.MainActivity
```

Смоук-тест на x86_64-эмуляторе (нужен `@opencode/cli-linux-x64-musl`):

```powershell
gradle :app:assembleDebug -PwithX86
```

## Первый запуск

1. Открыть приложение — сервер поднимается на свободном локальном порту (только `127.0.0.1`).
2. Меню (⋮ справа сверху) → **Provider / config editor**.
3. Вписать конфиг вручную или нажать **Add provider** и заполнить ID / имя / baseURL / apiKey / модель.
4. **Save & restart** — сервер перезапустится, провайдер появится в списке моделей веб-UI.

Пример конфига — [`examples/opencode.jsonc.example`](examples/opencode.jsonc.example).
Схема полностью совпадает с десктопной, отличие только в `shell: "/system/bin/sh"`.

Меню приложения: Reload page · Desktop user agent · Provider / config editor · Restart server ·
Show server log · Share server log file · Copy server URL · Stop server.

## Известные ограничения

- Только **arm64-v8a** (x86_64 — опционально, для эмулятора).
- Требуется **Android 8.0+** (minSdk 26).
- Сервер слушает только loopback; наружу порт не выставляется.
- APK большой (~130 МБ): внутри лежит весь Node-рантайм и JS-бандл OpenCode.
- Один инстанс сервера на приложение.
- Скачивание файлов из веб-UI перехватывается `DownloadListener` + JS-шимом для `blob:`
  (в веб-UI экспорт идёт через `URL.createObjectURL`, поэтому файл читается в момент клика).

## Структура репозитория

```
OpenCodeAndroid/            Android-проект (Kotlin, AGP 8.4.2)
  app/src/main/java/ai/opencode/mobile/
    MainActivity.kt         WebView, cookie, меню, скачивание, file chooser
    ServerService.kt        foreground service: запуск сервера, DNS-файлы, лог
    AppEnv.kt               пути, musl-загрузчик, симлинки рантайма, дефолтный конфиг
    ServerState.kt          состояние сервера + sink логов
    ConfigEditorActivity.kt редактор конфига и диалог «Add provider»
    Jsonc.kt                JSONC (комментарии/висячие запятые) + валидация
    OpenCodeAuth.kt         вычисление сессионной cookie
  app/src/main/jniLibs/     ld-musl-aarch64.so, libstdcxx.so, libgcc_s.so
                            (+ libopencode.so, генерируется build-native.ps1)
tools/
  build-native.ps1          скачать и пропатчить CLI из npm
  patch-binary.js           /etc/resolv.conf, /etc/hosts → относительные пути
  patch-musl.js             то же для musl-загрузчика
  deploy.ps1                сборка → установка → запуск → сбор логов
  make-config.js            сгенерировать android-конфиг из десктопного
  clean-config.js           убрать провайдера из конфига на устройстве
  cookie.js                 посчитать cookie вне приложения
  cdp.js                    CDP-драйвер для WebView (Runtime.evaluate)
examples/                   пример конфига
```

## Лицензия и благодарности

Проект — обёртка вокруг OpenCode. Сам OpenCode и его CLI-сборки распространяются по своей
лицензии (см. [sst/opencode](https://github.com/sst/opencode)); бинарники берутся из npm-пакетов
`@opencode/cli-linux-arm64-musl`. musl-загрузчик и `libstdc++`/`libgcc_s` — из Alpine Linux.
Код обёртки в этом репозитории можно использовать свободно.
