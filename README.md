# MultiAI Mobile IDE

AI-ассистент и IDE для Android от экосистемы MultiAI. Вход через частный OAuth-сервер
`https://multiai.store`, стриминг-чат, инструменты, терминал, голосовой ввод.

## Фичи

- Чистый минималистичный UI без топ-бара, тёмная тема
- Вход через MultiAI OAuth (Authorization Code + PKCE, native client)
- Стриминг ответов с посимвольной анимацией
- Сохранение истории чата, Markdown-рендеринг, подсветка кода
- Терминал и набор инструментов (toolchain)
- Голосовой ввод (Whisper), TTS-стриминг
- Синхронизация памяти и инструкций с облаком

## Структура

```
MultiAi IDE/
├── app/
│   ├── src/main/
│   │   ├── java/app/multiai/mobile/
│   │   │   ├── ui/              (ChatActivity, ProfileActivity, ChatModeActivity, …)
│   │   │   ├── network/
│   │   │   │   └── oauth/       (OAuthConfig, OAuthClient, OAuthStore, IdTokenValidator, …)
│   │   │   ├── terminal/        (PersistentShell, ToolsManager, TerminalService)
│   │   │   ├── adapters/
│   │   │   ├── models/
│   │   │   └── utils/
│   │   ├── res/                 (layout/, drawable/, values/, values-ru/, values-en/)
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── OAUTH_REGISTRATION.md        (инструкция регистрации для владельца MultiAI)
├── build.gradle
└── settings.gradle
```

## Сборка

Требуется JDK 21 (Android Gradle Plugin). На Windows запускать через `gradlew.bat`
с явно экспортированным `JAVA_HOME`:

```bash
export JAVA_HOME='C:/Program Files/Java/jdk-21'
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew.bat :app:assembleDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

## Установка на устройство

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Тесты

```bash
./gradlew.bat :app:testDebugUnitTest
```

Юнит-тесты OAuth-примитивов (PKCE, ID-token validator) — без Android-зависимостей,
чистый JUnit4.

## OAuth-интеграция

Подробная инструкция по регистрации приложения в MultiAI и подключению `client_id` —
в [`OAUTH_REGISTRATION.md`](OAUTH_REGISTRATION.md). Контракт интеграции описан в
`oauth-client-integration-ai.md`.
