# OpenCode для Android

Полноценный порт [OpenCode](https://opencode.ai) на Android: **настоящий сервер OpenCode**
(официальный musl-бинарник CLI v2.2.5) запускается внутри APK, а его официальный веб-интерфейс
открывается в WebView. Это не заглушка и не урезанный мобильный UI — внутри работает тот же
движок, что и на десктопе: тот же конфиг, те же провайдеры, те же инструменты (shell, read/write,
grep, edit), та же сессия и та же история.

Сверху — мобильные удобства: **голосовой ввод**, **встроенный терминал**, **полноценный Python
3.12 с pip**, **привязка своего GitHub и Google Drive** для работы с файлами и **локальный ИИ**
(llama.cpp с GGUF-моделью прямо на телефоне, без интернета).

## Что работает

| Возможность | Статус |
|---|---|
| Сервер OpenCode v2.2.5 внутри APK (aarch64-musl) | ✅ |
| Официальный веб-UI v2 в WebView, cookie-авторизация | ✅ |
| Реальная генерация через любой провайдер | ✅ |
| Инструмент `shell` (toybox `/system/bin/sh`) | ✅ |
| **Голосовой ввод** — микрофон в тулбаре, текст вставляется в поле веб-UI | ✅ |
| **Терминал** — экран с интерактивным `sh`, общий `PATH` с агентом | ✅ |
| **Python 3.12 + pip** внутри APK, виден и агенту, и терминалу | ✅ |
| **GitHub** — вход по device flow, клон репозитория в `~/work`, пуш файла | ✅ |
| **Google Drive** — вход по OAuth (PKCE), список, скачивание, загрузка | ✅ |
| **Локальный ИИ** — llama.cpp внутри APK, GGUF на устройстве, офлайн | ✅ |
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
│   │    │  mic ──► SpeechRecognizer ──► JS-вставка в активное поле              │
│   │    ▼                              ▲                                        │
│   │  cookie opencode_session_<port>=<exp>.<hmac>                               │
│   ▼                                   │                                        │
│  ServerService (foreground) ──────────┘                                        │
│   │ exec                                                                       │
│   ▼                                                                            │
│  ld-musl-aarch64.so --library-path … libopencode.so serve --port <port>         │
│   │                                                                            │
│   ├─ cwd = $HOME (files/home), конфиг в $HOME/.config/opencode/                 │
│   ├─ PATH = files/bin : $HOME/.local/bin : /system/bin : …                     │
│   │     └─ files/bin/python3, pip3 — обёртки над musl-рантаймом CPython         │
│   └─ spawn ──► /system/bin/sh (инструмент `shell`, TerminalActivity)           │
│                                                                                │
│  IntegrationsActivity ──► api.github.com / www.googleapis.com (HTTPS)          │
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
5. **Одно окружение на всех.** `ShellEnv` — единственный источник `PATH`/`HOME`/`XDG_*`/`PYTHON*`
   для сервера, терминала и всех их потомков. Именно поэтому `python`, `pip` и обычные
   Android-утилиты одинаково доступны и модели, и человеку в терминале.
6. **Python — musl-сборка.** CPython для `aarch64-unknown-linux-musl` (тот же libc, что у CLI)
   распаковывается из `assets/python.zip` в `files/python`; запускается через musl-загрузчик
   с `--library-path`, а в `files/bin` кладутся шелл-обёртки `python`/`python3`/`pip`/`pip3`.
   OpenSSL внутри статический, корневые сертификаты — `files/python/ssl/cacert.pem`.

## Голосовой ввод

Кнопка-микрофон в тулбаре главного экрана (рядом с ⋮).

1. Нажать микрофон → выдать разрешение `RECORD_AUDIO` (спрашивается один раз).
2. Говорить; распознанный текст подставляется **в то поле веб-UI, которое сейчас в фокусе**,
   а если ни одно не в фокусе — в нижнее видимое поле ввода (композер чата).
3. Текст вставляется как «настоящий» ввод (`document.execCommand('insertText')`, с откатом на
   прямую вставку + синтетическое событие `input`), поэтому SolidJS-стор веб-UI видит изменение
   и кнопка отправки активируется.

Распознавание — системный `SpeechRecognizer` (Google/офлайн-пакет распознавания устройства).
Интернет не обязателен, если на устройстве скачан офлайн-языковой пакет.

## Терминал

Меню ⋮ → **Terminal**. Это постоянный `/system/bin/sh` внутри песочницы приложения:

- `cwd = $HOME` (`/data/data/ai.opencode.mobile/files/home`), `PATH` — тот же, что у агента;
- `python`, `pip`, `sh`, `ls`, `grep`, `curl`-подобные toybox-утилиты работают сразу;
- быстрые кнопки: `python`, `pip`, `~`, `~/work`, `env`, «новая оболочка», «очистить», «стоп»;
- шелл живёт в синглтоне `TerminalSession`, поэтому поворот экрана и уход из активити его
  не убивают; вывод хранится в кольцевом буфере на 3000 строк.

Это **pipe-backed** шелл, а не PTY (Android не даёт публичного PTY API из Java): построчные
команды, пайпы, редиректы, скрипты и интерактивные программы, которым достаточно stdin, работают;
полноэкранные curses-UI (`vim`, `htop`, `top`) — нет.

## Python

Python 3.12.16 (CPython, musl aarch64) лежит в APK и распаковывается при первом запуске сервера:

```
files/python/bin/python3.12      интерпретатор (линкуется только с musl libc)
files/python/lib/python3.12/     stdlib + ensurepip
files/python/ssl/cacert.pem      корневые сертификаты для https://
files/bin/python, python3        обёртки (exec через musl-загрузчик)
files/bin/pip, pip3              обёртки (python -m pip)
```

- Первый запуск: распаковка ~1230 файлов и однократный `ensurepip` (несколько секунд, прогресс
  виден в логе сервера).
- `pip install <пакет>` работает; колёса ставятся в `files/python/lib/python3.12/site-packages`.
  Для пакетов с нативным кодом нужны **manylinux/musllinux aarch64**-колёса — сборка из
  исходников без NDK не пройдёт.
- Агент видит Python через инструмент `shell`: достаточно `python3 script.py`, а скрипты с
  `#!/usr/bin/env python3` запускаются как обычно.
- Обновить/пересобрать рантайм: `tools/build-python.ps1` → `app/src/main/assets/python.zip`
  (в git не коммитится, 26 МБ).

## Локальный ИИ

Меню ⋮ → **Локальный ИИ**. Модель работает **на самом устройстве**, без интернета и без
облачных провайдеров: внутри APK лежит собранный из исходников `llama.cpp` (`llama-server`),
который запускается дочерним процессом на `127.0.0.1`.

Отдельного чата здесь нет намеренно — он уже есть в opencode. Пока движок работает, приложение
дописывает в `opencode.jsonc` провайдера `local` (`@opencode/ai/providers/openai-compatible`,
`baseURL: http://127.0.0.1:<порт>/v1`) и перезапускает сервер, после чего модель появляется
в **обычном списке моделей** веб-UI и с ней можно работать как с любой другой.

1. **Модель** → выбрать из списка → **Загрузить** (GGUF кладётся в `~/models`, ~0.5–2 ГБ)
   или **Указать файл…** и дать путь к своему `.gguf`.
2. **Параметры** — размер контекста (по умолчанию 4096 токенов) и число потоков (0 = авто).
3. **Запустить** — `llama-server` поднимается на свободном порту, `/health` ждётся до 180 с,
   затем провайдер попадает в конфиг. В opencode остаётся выбрать модель `local`.
4. **Остановить** — процесс убивается, провайдер `local` удаляется из конфига, чтобы там не
   висела модель, которая не может ответить.

Каталог моделей (ссылки проверены, качается напрямую с Hugging Face):

| Модель | Размер | Для чего |
|---|---|---|
| Qwen2.5-0.5B-Instruct | 491 МБ | самый быстрый, короткие ответы |
| Qwen2.5-1.5B-Instruct | 1.12 ГБ | баланс качества и скорости |
| Qwen2.5-Coder-1.5B | 1.12 ГБ | автодополнение кода, рефакторинг |
| Gemma-3-1B-it | 806 МБ | многоязычные ответы, пересказы |
| Qwen3-1.7B | 1.28 ГБ | пошаговые рассуждения |
| Qwen2.5-Coder-3B | 1.93 ГБ | лучшее качество, нужно 8 ГБ ОЗУ |

**Язык интерфейса.** У экрана нет своего переключателя языка: `LocalAiLocale` читает cookie
`oc_locale`, которую пишет сам opencode, когда вы меняете язык в его настройках, и применяет
ту же локаль к своим ресурсам. Выбрали в opencode «Русский» — переведётся и пункт меню,
и весь экран локального ИИ. Сейчас переведены `ru`, `uk`, `de`; для остальных локалей
показывается английский текст.

`llama-server` собран под **arm64-v8a**, линкуется с bionic (`/system/bin/linker64`) и поэтому
запускается напрямую, без musl-загрузчика. Веб-UI внутри бинарника отключён (`--no-webui`) —
он не нужен. В APK он лежит как `jniLibs/arm64-v8a/libllama-server.so` (AGP пакует только
`*.so`), ужат `llvm-strip --strip-all` до ~15 МБ.

## GitHub и Google Drive

Меню ⋮ → **GitHub & Google Drive**. Для GitHub `client_id` уже вшит в сборку и подставлен
в поле — достаточно нажать **Connect**. Для Google Drive нужен **свой** `client_id` (см. ниже).

### GitHub (OAuth App + device flow)

В сборке уже есть OAuth App (`Ov23lii3yOP22kR3xYV9`), поэтому поле *Client ID* заполнено
заранее: нажмите **Connect** и подтвердите код на `github.com/login/device`.

Свой OAuth App нужен, только если хотите отдельные лимиты или свой аккаунт-владелец:

1. Открыть <https://github.com/settings/developers> → **OAuth Apps** → **New OAuth App**.
2. Заполнить ровно так:

   | Поле | Значение |
   |---|---|
   | **Application name** | `OpenCode Android` |
   | **Homepage URL** | `https://opencode.ai` |
   | **Authorization callback URL** | `https://opencode.ai` |

   Callback для device flow не используется — важно только, что поле не пустое.
3. В созданном приложении поставить галочку **Enable Device Flow** и нажать **Update**.
4. Скопировать **Client ID** (Client secret не нужен) и вставить в приложении в поле
   *GitHub OAuth App Client ID* → **Connect**.
5. Приложение покажет код и кнопку «Open page»: подтвердить код на `github.com/login/device`.
   Токен сохранится, повторный вход не нужен.

Что умеет: **Clone repository** — список ваших репозиториев, выбранный скачивается zipball'ом
в `~/work/<repo>` (префикс `<owner>-<repo>-<sha>/` срезается, получается обычный чекаут);
**Push file** — залить/обновить один файл в репозитории (локальный путь относительно `~`,
путь внутри репозитория, сообщение коммита). Скоуп — `repo read:user`.

> Git-бинарника в Android нет, поэтому работа идёт через REST API: это «скачать» и «залить файл»,
> а не полноценный `git clone/push`. История, ветки и merge — на десктопе.

### Google Drive (OAuth client, PKCE)

1. Открыть <https://console.cloud.google.com> → создать (или выбрать) проект.
2. **APIs & Services → Library** → включить **Google Drive API**.
3. **APIs & Services → OAuth consent screen**: тип **External**, заполнить название и e-mail,
   добавить свой Google-аккаунт в **Test users** (пока приложение в статусе Testing, без этого
   будет `access_denied`).
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**.
5. **Application type** — `Desktop app`. Секрет не нужен, нужен только **Client ID**.
6. Вставить Client ID в приложении в поле *Google OAuth Client ID (Desktop app)* → **Connect** →
   откроется браузер, войти и разрешить доступ. Ответ ловится локальным loopback-сервером
   на `127.0.0.1:<случайный порт>`, токен (с refresh) сохраняется.

Что умеет: **Drive files** — список файлов, тап скачивает в `~/work`; **Upload file** — загрузить
файл из песочницы (`~/work/...`) в Drive под заданным именем. Скоуп — полный
`https://www.googleapis.com/auth/drive` (чтобы видеть и существующие файлы, а не только созданные
приложением).

## Сборка

Нужно: JDK 17, Android SDK с `platforms;android-34` и `build-tools;34.0.0`, Node.js (для патча
бинарника), Gradle 8.7 (или Android Studio).

```powershell
# 1. Забрать и пропатчить нативный бинарник (~235 МБ, в git его нет)
powershell -ExecutionPolicy Bypass -File tools/build-native.ps1

# 2. Собрать встроенный Python (~26 МБ, в git его нет)
powershell -ExecutionPolicy Bypass -File tools/build-python.ps1

# 3. Собрать APK
cd OpenCodeAndroid
gradle :app:assembleDebug
```

Готовый APK: `OpenCodeAndroid/app/build/outputs/apk/debug/app-debug.apk` (~166 МБ, arm64-v8a).

`llama-server` для локального ИИ уже лежит в репозитории
(`jniLibs/arm64-v8a/libllama-server.so`, 15 МБ) — NDK для обычной сборки не нужен. Пересобрать
его из исходников (нужны NDK r27+ и CMake 3.22+):

```powershell
git clone --depth 1 https://github.com/ggml-org/llama.cpp
cmake -B build-llama -S llama.cpp -G Ninja -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DLLAMA_CURL=OFF -DGGML_OPENMP=OFF `
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=ON
cmake --build build-llama --target llama-server -j
llvm-strip --strip-all build-llama/bin/llama-server
```

Установка на устройство:

```powershell
adb install -r -g OpenCodeAndroid\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n ai.opencode.mobile/.MainActivity
```

Смоук-тест на x86_64-эмуляторе (нужен `@opencode/cli-linux-x64-musl`; Python в x86-сборке
не поставляется):

```powershell
gradle :app:assembleDebug -PwithX86
```

## Первый запуск

1. Открыть приложение — сервер поднимается на свободном локальном порту (только `127.0.0.1`),
   в это же время распаковывается Python.
2. Меню (⋮ справа сверху) → **Provider / config editor**.
3. Вписать конфиг вручную или нажать **Add provider** и заполнить ID / имя / baseURL / apiKey / модель.
4. **Save & restart** — сервер перезапустится, провайдер появится в списке моделей веб-UI.

Пример конфига — [`examples/opencode.jsonc.example`](examples/opencode.jsonc.example).
Схема полностью совпадает с десктопной, отличие только в `shell: "/system/bin/sh"`.

Меню приложения: Reload page · Desktop user agent · Provider / config editor · Terminal ·
GitHub & Google Drive · Local AI · Restart server · Show server log · Share server log file ·
Copy server URL · Stop server.

## Известные ограничения

- Только **arm64-v8a** (x86_64 — опционально, для эмулятора). Python — только arm64.
- Требуется **Android 8.0+** (minSdk 26).
- `targetSdk` намеренно оставлен на **28**, а не на актуальном: начиная с Android 10 система
  запрещает `execve` файлов внутри приватного каталога приложения для всего, что таргетит API 29+.
  С `targetSdk 34` любой скрипт и обёртка в `files/` падали с `Permission denied` (rc 126), то есть
  встроенный Python был неработоспособен. Termux закрепляет то же значение по той же причине.
  Побочный эффект — старый `targetSdk` включает режим совместимости для части API; для
  приложения-обёртки с WebView и локальным сервером это несущественно.
- Сервер слушает только loopback; наружу порт не выставляется.
- APK большой (~166 МБ): внутри весь Node-рантайм и JS-бандл OpenCode, CPython и llama.cpp.
- Один инстанс сервера на приложение.
- Терминал — pipe-backed (нет PTY): полноэкранные curses-программы не работают.
- GitHub-интеграция — REST (клон zipball'ом и пуш одного файла), без `git`.
- Для Google Drive нужен **свой** OAuth client (Desktop app); GitHub-приложение уже вшито
  (`Ov23lii3yOP22kR3xYV9`), но свой `client_id` можно вписать вместо него.
- Скачивание файлов из веб-UI перехватывается `DownloadListener` + JS-шимом для `blob:`
  (в веб-UI экспорт идёт через `URL.createObjectURL`, поэтому файл читается в момент клика).
- Локальный ИИ: только **arm64-v8a**, модель целиком в ОЗУ — для 3B нужно ~8 ГБ памяти.
  Движок один, порт локальный, наружу не выставляется; при остановке провайдер `local`
  удаляется из конфига.

## Структура репозитория

```
OpenCodeAndroid/            Android-проект (Kotlin, AGP 8.4.2)
  app/src/main/assets/
    python.zip              CPython 3.12 musl-aarch64 (генерируется build-python.ps1)
  app/src/main/java/ai/opencode/mobile/
    MainActivity.kt         WebView, cookie, меню, скачивание, file chooser, голосовой ввод
    ServerService.kt        foreground service: запуск сервера, DNS-файлы, лог, bootstrap Python
    AppEnv.kt               пути, musl-загрузчик, симлинки рантайма, дефолтный конфиг
    ShellEnv.kt             общий PATH/HOME/XDG/PYTHON для сервера, терминала и агента
    PythonRuntime.kt        распаковка, ensurepip, обёртки python/pip
    TerminalSession.kt      постоянный `sh` (pipe-backed), буфер вывода
    TerminalActivity.kt     экран терминала
    IntegrationsActivity.kt экран GitHub & Google Drive
    GitHubIntegration.kt    device flow, список репо, zipball, PUT contents
    DriveIntegration.kt     OAuth PKCE + loopback-редирект, list/download/upload
    IntegrationStore.kt     сохранение client_id и токенов
    LocalAiActivity.kt      экран локального ИИ: модель, параметры, старт/стоп
    LocalAiRuntime.kt       запуск llama-server, каталог моделей, провайдер в конфиге
    LocalAiStore.kt         выбранная модель и параметры движка
    LocalAiLocale.kt        язык экрана = язык opencode (cookie oc_locale)
    HttpJson.kt             HTTP/JSON на HttpURLConnection
    ServerState.kt          состояние сервера + sink логов
    ConfigEditorActivity.kt редактор конфига и диалог «Add provider»
    Jsonc.kt                JSONC (комментарии/висячие запятые) + валидация
    OpenCodeAuth.kt         вычисление сессионной cookie
  app/src/main/jniLibs/     ld-musl-aarch64.so, libstdcxx.so, libgcc_s.so, libllama-server.so
                            (+ libopencode.so, генерируется build-native.ps1)
tools/
  build-native.ps1          скачать и пропатчить CLI из npm
  build-python.ps1          собрать assets/python.zip из python-build-standalone
  patch-binary.js           /etc/resolv.conf, /etc/hosts → относительные пути
  patch-musl.js             то же для musl-загрузчика
  elf-info.js               разбор ELF (PT_INTERP, DT_NEEDED) для отладки
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
CPython — из [python-build-standalone](https://github.com/astral-sh/python-build-standalone)
(PSF License). Код обёртки в этом репозитории можно использовать свободно.
