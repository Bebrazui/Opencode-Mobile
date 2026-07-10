# OpenCode Mobile

Android-интерфейс для [OpenCode](https://github.com/opencode-ai/opencode) — AI-ассистента для кода.

## Архитектура

```
┌─────────────────────────────────────────────────────────────┐
│                    Android App (Kotlin)                     │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │  Chat Screen │  │ Model Picker │  │  Session List     │  │
│  │  (Compose)   │  │  (Compose)   │  │  (Compose)        │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬──────────┘  │
│         │                 │                   │             │
│         └─────────────────┼───────────────────┘             │
│                           ▼                                 │
│              ┌────────────────────────┐                     │
│              │  ApiClient (OkHttp)    │                     │
│              │  - REST (models, ses)  │                     │
│              │  - SSE (streaming)     │                     │
│              └───────────┬────────────┘                     │
└───────────────────────────│─────────────────────────────────┘
                            │ HTTP/WebSocket
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              Proot/Alpine (linux-arm64-musl)                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  opencode-server (binary, ~103MB)                    │   │
│  │  - HTTP server on 0.0.0.0:PORT                       │   │
│  │  - Bun runtime + all dependencies                    │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Требования

- Android 7.0+ (API 24)
- ARM64 (aarch64)
- ~200MB места на устройстве

## Сборка

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

# Скопировать в Android проект
cp dist/opencode-server /path/to/Opencode-Mobile/app/src/main/assets/
```

### 2. Собрать APK

```bash
git clone https://github.com/Bebrazui/Opencode-Mobile.git
cd Opencode-Mobile

export JAVA_HOME=~/jdk17/jdk-17.0.2
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug
```

## Структура проекта

```
app/
  └── src/main/
      ├── java/ai/opencode/mobile/
      │   ├── MainActivity.kt        # Главная Activity
      │   ├── TermuxManager.kt        # Управление proot + Alpine + opencode-server
      │   └── BackendService.kt       # Foreground service
      ├── jniLibs/arm64-v8a/          # Proot бинарники (libproot-xed.so, libproot.so, libtalloc.so)
      ├── assets/
      │   ├── alpine.rootfs           # Минимальный Alpine (3.7MB)
      │   └── opencode-server         # Бинарик сервера (~103MB)
      └── res/                        # UI ресурсы

terminal-emulator/                     # Termux terminal library
```

## Что сделано

- [x] Proot + Alpine Linux (musl) на ARM64
- [x] DNS-резолв для скачивания пакетов
- [x] Установка пакетов через `apk add`
- [x] Серверный бинарик opencode (HTTP API + WebSocket)
- [ ] Kotlin UI (Chat, Model Picker)
- [ ] SSE стриминг

## Лицензия

MIT
