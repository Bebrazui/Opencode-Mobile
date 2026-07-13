# OpenCode Mobile

Android-клиент для [OpenCode](https://github.com/opencode-ai/opencode) — локального AI-ассистента для работы с кодом. Сервер запускается прямо на телефоне в изолированном окружении proot/Alpine, а интерфейс открывается в WebView. Ничего не уходит в облако, кроме самого UI (статические файлы с `app.opencode.ai`) и запросов к выбранным вами LLM-провайдерам.

## Что это

Приложение превращает Android-смартфон в автономное рабочее место для AI-программирования:

- **Сервер** — бинарь `opencode-server` (скомпилированный bun, ~141 МБ) крутится в proot/Alpine Linux (musl) на ARM64 и отдаёт HTTP API + SSE-стриминг на `localhost`.
- **Клиент** — `MainActivity` с `WebView`, которая грузит веб-UI opencode (по сети с `app.opencode.ai`, офлайн — из кэша на диске).
- **Терминал** — встроена библиотека эмулятора терминала (форк Termux) для proot-окружения.

## Архитектура

```
┌──────────────────────────────────────────────────────────────┐
│                   Android App (Kotlin)                       │
│  ┌───────────────┐   ┌────────────────────────────────────┐   │
│  │ MainActivity  │   │  WebView (UI opencode)             │   │
│  │  - WebView    │◄──│  - грузит app.opencode.ai          │   │
│  │  - SSE proxy  │   │  - кэш на диске (оффлайн)          │   │
│  │  - AndroidBridge (JS-интерфейс):                       │   │
│  │      folder picker, clipboard image paste, логи        │   │
│  └──────┬────────┘   └────────────────────────────────────┘   │
│         │ HTTP (localhost)                                      │
│         ▼                                                       │
│  ┌──────────────────────────────────────────────────────┐     │
│  │  BackendService (foreground)                          │     │
│  │    └─ TermuxManager: proot + Alpine (musl, ARM64)     │     │
│  │         └─ opencode-server (bun binary, ~141 MB)      │     │
│  │              - HTTP server на localhost:PORT          │     │
│  │              - SQLite-БД сессий (opencode-dev.db)     │     │
│  └──────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────┘
```

## Возможности

- [x] Полностью локальный сервер opencode в proot/Alpine (ARM64)
- [x] Веб-интерфейс opencode в WebView с кэшем (работает и оффлайн)
- [x] Выбор папки проекта через системный проводник Android (SAF) — `AndroidBridge`
- [x] Вставка изображений из буфера обмена в поле ввода (инъекция JS)
- [x] В proot доступны `git`, `node`, `diffutils` (устанавливаются при первом запуске)
- [x] SSE-стриминг через WebView без буферизации (прокси в `MainActivity`)
- [x] Сохранение чатов в локальной SQLite (патч PRAGMA: `journal_mode=OFF`, `synchronous=FULL`, `mmap_size=0` — надёжно под proot)
- [x] Встроенный терминал proot-окружения

## Требования

- Android 7.0+ (API 24)
- ARM64 (aarch64)
- ~200 МБ свободного места
- Доступ к интернету при первом запуске (для загрузки UI и пакетов Alpine)

## Сборка

### 0. Окружение

```bash
export JAVA_HOME=~/jdk17/jdk-17.0.2
export ANDROID_HOME=/opt/android-sdk
```

### 1. Собрать opencode-server бинарик

Из репозитория [opencode](https://github.com/opencode-ai/opencode):

```bash
git clone https://github.com/opencode-ai/opencode.git
cd opencode
bun install

# Собрать серверный бинарик для linux-arm64-musl
cd packages/opencode
bun build --compile --minify \
  --target=bun-linux-arm64-musl \
  --outfile dist/opencode-server \
  src/server-entry.ts

# Скопировать в Android-проект
cp dist/opencode-server /path/to/Opencode-Mobile/app/src/main/assets/
```

> Бинарик уже пропатчен на уровне исходников сборки: в блоке PRAGMA
> выставлены `journal_mode = OFF`, `synchronous = FULL`, `mmap_size = 0`
> для надёжной записи БД под proot.

### 2. Собрать APK

```bash
git clone https://github.com/Bebrazui/Opencode-Mobile.git
cd Opencode-Mobile
./gradlew assembleDebug
```

Debug-APK появится в `app/build/outputs/apk/debug/`.

### 3. Релизная сборка (подпись)

Для `assembleRelease` нужен собственный keystore. Создайте его и
положите настройки в `local.properties` (он в `.gitignore`, секретом не
станет в репозитории):

```bash
keytool -genkeypair -v \
  -keystore app/release-key.keystore -alias opencode \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "<пароль>" -keypass "<пароль>" \
  -dname "CN=OpenCode Mobile, OU=Mobile, O=Bebrazui, C=US"
```

`local.properties`:

```
RELEASE_STORE_FILE=app/release-key.keystore
RELEASE_STORE_PASSWORD=<пароль>
RELEASE_KEY_ALIAS=opencode
RELEASE_KEY_PASSWORD=<пароль>
```

```bash
./gradlew assembleRelease
```

Подписанный APK: `app/build/outputs/apk/release/app-release.apk`.

> `targetSdk = 28` выбран намеренно (legacy-доступ к файловой системе для
> извлечения proot/Alpine). Линт-проверка `ExpiredTargetSdkVersion` для
> релиза отключена, т.к. приложение распространяется не через Google Play.

## Установка

1. Перенесите APK на телефон и установите (разрешите «Unknown sources»).
2. При первом запуске приложение скачает/распакует Alpine и бинарь сервера
   (нужен интернет и ~200 МБ места).
3. В интерфейсе укажите API-ключ выбранного LLM-провайдера.

## Использование

- **Выбор проекта** — кнопка выбора папки открывает системный проводник
  Android (SAF); доступна любая папка, включая внешнюю память.
- **Вставка скриншота/фото** — скопируйте изображение в буфер обмена и
  вставьте (`Ctrl+V` / долгое нажатие) в поле ввода — оно добавится как
  вложение.
- **Терминал** — proot-окружение с `git`, `node`, `diffutils` доступно
  встроенным терминалом.

## Приватность

- Сервер opencode работает только на `localhost` телефона.
- История чатов хранится локально в SQLite (`opencode-dev.db`).
- Веб-UI грузится со `app.opencode.ai` (статика) при наличии сети и кэшируется.
- Запросы к LLM уходят напрямую к провайдеру по ключу, который вы ввели.

## Известные ограничения

- APK крупный (~60 МБ релиз / ~136 МБ debug) из-за вшитого бинаря сервера.
- Бинарь сервера нужно пересобирать при обновлении opencode (UI берётся с
  апстрима, правки `packages/app` не попадают в устройство без пересборки
  бинаря).
- `targetSdk = 28`: на новых Android приложение работает в режиме
  совместимости.

## Лицензия

MIT
