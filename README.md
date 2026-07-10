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
│  │  opencode-server (binary)                            │   │
│  │  - HTTP server on 0.0.0.0:PORT                       │   │
│  │  - No TUI, no embedded Web UI                        │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Требования

- Android 7.0+ (API 24)
- ARM64 (aarch64)
- ~50MB места на устройстве

## Сборка

```bash
# Клонировать
git clone https://github.com/Bebrazui/Opencode-Mobile.git
cd Opencode-Mobile

# Собрать APK
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
      │   ├── TermuxManager.kt        # Управление proot + Alpine
      │   └── BackendService.kt       # Foreground service
      ├── jniLibs/arm64-v8a/          # Proot бинарники
      ├── assets/
      │   └── alpine.rootfs           # Минимальный Alpine (3.7MB)
      └── res/                        # UI ресурсы

terminal-emulator/                     # Termux terminal library
```

## Что сделано

- [x] Proot + Alpine Linux (musl) на ARM64
- [x] DNS-резолв для скачивания пакетов
- [x] Установка пакетов через `apk add`
- [ ] Серверный бинарик opencode
- [ ] Kotlin UI (Chat, Model Picker)
- [ ] SSE стриминг

## Лицензия

MIT
