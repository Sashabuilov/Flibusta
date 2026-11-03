# Флибуста — Android app

Версия: `0.0.1_2026`  
Application ID: `ru.flibusta.app`  
Минимальная версия Android: 6.0 (API 23)

## Сборка APK

Требуется Android Studio (или Android SDK + Gradle) и подключение к Google Maven/Gradle Plugin Portal.

1. Откройте папку `Flibusta` в Android Studio.
2. Дождитесь Gradle Sync (проект использует Android Gradle Plugin 8.7.3 и compileSdk 35).
3. Выберите `Build → Build Bundle(s) / APK(s) → Build APK(s)`.
4. APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

Из командной строки после установки Android SDK:

```bash
gradle assembleDebug
```

## Реализация

- Нативный Java UI без сторонних библиотек.
- OPDS Atom XML, поиск книг по `searchType=books`, поиск автора по `searchType=authors`.
- Автоповтор соединения с `/opds` с экспоненциальной паузой до 30 секунд.
- Загрузка файла через системный Android DownloadManager в `Downloads`.
- Обложка из вложения используется как launcher icon.

Примечание: Flibusta может менять структуру OPDS-фида и доступность домена. Поиск автора пробует получить OPDS-ленты авторов из ссылок каталога, а если сервер уже вернул книги — отображает их напрямую.
