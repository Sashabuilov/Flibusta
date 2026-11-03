# Флибуста — Android app

Версия: `0.8.0.2026`

Application ID: `ru.flibusta.app`

Минимальная версия Android: 6.0 (API 23)

## Что нового в 0.0.2_2026
- ✨ Полностью переработанный интерфейс в стиле **Material You (Material 3)** с поддержкой светлой и темной темы.
- 📚 **Приоритет загрузки в формате EPUB** (если доступен в каталоге).
- 🔄 Добавлена кнопка **"Сбросить"** для быстрой очистки результатов поиска и строки ввода.
- 🛠 Исправлена видимость выбранного элемента в выпадающем меню (заменен на Exposed Dropdown Menu).

## Сборка APK

Требуется Android Studio (или Android SDK + Gradle) и подключение к Google Maven/Gradle Plugin Portal.

1. Откройте папку `Flibusta` в Android Studio.
2. Дождитесь Gradle Sync (проект использует Android Gradle Plugin и compileSdk 34).
3. Выберите `Build → Build Bundle(s) / APK(s) → Build APK(s)`.
4. APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

Из командной строки после установки Android SDK: