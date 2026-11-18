# 🎙️ BackRecorder

**English** · [Русский](#русский)

A voice recorder that remembers the past. Like "instant replay" in screen-recording apps, but for sound: the app keeps the **last N minutes** of microphone audio in memory (from 1 minute up to 2 hours). Someone said something important? Tap "Save" and you get a recording of what happened **before** you tapped. Older audio is deleted automatically.

## Features
- Buffer length: 1, 5, 10, 15, 30, 45 min, 1, 1.5 or 2 hours, with the storage each option takes shown next to it.
- Save the last 1 / 5 / 15 minutes or the whole buffer, from the app or right from the notification.
- Works in the background and with the screen off.
- `.m4a` files are saved to `Recordings/BackRecorder` (`Music/BackRecorder` on Android 10–11), and there's an "Open folder" button.
- 10 languages: English, Deutsch, Español, Français, Italiano, Polski, Português, Türkçe, Українська, Русский.
- "Delete all data" button (buffer only, or buffer + saved recordings).
- No ads, no internet access, nothing is sent anywhere.

| Buffer | Storage |
|---|---|
| 1 min | ~0.5 MB |
| 15 min | ~7 MB |
| 1 hour | ~28 MB |
| 2 hours | ~57 MB |

## Installing on your phone (Android 10 or newer)

1. On your phone, open the **[Releases](../../releases/latest)** page of this repository.
2. Download **`BackRecorder.apk`**.
3. Open the downloaded file. Android will ask about installing from unknown sources: tap **Settings → allow it for your browser / file manager**, go back, then tap **Install**.
   - If Google Play Protect says "App not verified", tap **More details → Install anyway**. This happens with every app that isn't from the Play Store.
4. Launch **BackRecorder** and allow **microphone** and **notification** access.
5. Tap **"Disable battery optimization for the app"** → Allow. Without it, the phone may kill recording in the background.
   - **Xiaomi / Redmi / POCO:** in the app's settings also turn on "Autostart" and set "Battery saver" to "No restrictions".
   - **Samsung:** Settings → Battery → Background usage limits → make sure BackRecorder is not in "Sleeping apps".
   - **Huawei / Honor:** Battery → App launch → BackRecorder → Manage manually, turn on all three switches.

### Updating
Download the new `BackRecorder.apk` from Releases and install it over the old one. Your settings and recordings are kept.

## Building from source
1. Install [Android Studio](https://developer.android.com/studio).
2. `File → Open` → the project folder, wait for Gradle Sync.
3. `Build → Build App Bundle(s) / APK(s) → Build APK(s)`, or from a terminal:
   ```
   gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`.

## How it works
- `RingRecorder`: microphone → AAC 64 kbps, written in 30-second chunks to internal storage; chunks beyond the buffer length are deleted.
- `AacExporter`: joins the chunks into one `.m4a` without re-encoding.
- `BufferService`: foreground service with a notification.
- `MainActivity`: the app screen.

---

## Русский

Диктофон, который помнит прошлое. Работает как «повтор» в программах записи экрана, только для звука: приложение постоянно слушает микрофон и держит в памяти **последние N минут** (от 1 минуты до 2 часов). Что-то важное сказали — жмёшь «Сохранить» и получаешь запись того, что было **до** нажатия. Старое удаляется само.

### Возможности
- Длина буфера: 1, 5, 10, 15, 30, 45 минут, 1, 1.5 или 2 часа (рядом показано, сколько места займёт).
- Сохранить последние 1 / 5 / 15 минут или весь буфер — из приложения или прямо из уведомления.
- Работает в фоне и с выключенным экраном.
- Файлы `.m4a` сохраняются в `Recordings/BackRecorder` (на Android 10–11 — `Music/BackRecorder`), есть кнопка «Открыть папку».
- 10 языков: English, Deutsch, Español, Français, Italiano, Polski, Português, Türkçe, Українська, Русский.
- Кнопка «Удалить все данные» (только буфер или буфер + сохранённые записи).
- Без рекламы, без интернета, никуда ничего не отправляет.

| Буфер | Место |
|---|---|
| 1 мин | ~0.5 МБ |
| 15 мин | ~7 МБ |
| 1 час | ~28 МБ |
| 2 часа | ~57 МБ |

### Установка на телефон (Android 10 и новее)

1. Открой на телефоне страницу **[Releases](../../releases/latest)** этого репозитория.
2. Скачай файл **`BackRecorder.apk`**.
3. Открой скачанный файл. Android спросит про установку из неизвестных источников — нажми **«Настройки» → разреши для браузера/файлового менеджера** → вернись назад → **«Установить»**.
   - Если Google Play Защита предупредит «Приложение не проверено» — нажми **«Подробнее» → «Всё равно установить»**. Так бывает со всеми приложениями не из Play Маркета.
4. Запусти **BackRecorder**, разреши доступ к **микрофону** и **уведомлениям**.
5. Нажми **«Отключить экономию батареи для приложения»** → «Разрешить». Без этого телефон может закрыть запись в фоне.
   - **Xiaomi / Redmi / POCO:** ещё в настройках приложения включи «Автозапуск» и в «Контроле активности» выбери «Нет ограничений».
   - **Samsung:** Настройки → Батарея → Ограничения в фоновом режиме → убедись, что BackRecorder не в списке «Спящие приложения».
   - **Huawei / Honor:** Батарея → Запуск приложений → BackRecorder → управлять вручную, включить все три переключателя.

#### Обновление
Скачай новый `BackRecorder.apk` из Releases и установи поверх — настройки и записи сохранятся.

### Сборка из исходников
1. Установи [Android Studio](https://developer.android.com/studio).
2. `File → Open` → папка проекта, дождись Gradle Sync.
3. `Build → Build App Bundle(s) / APK(s) → Build APK(s)`, или в терминале `gradlew assembleDebug`.
   Готовый файл: `app/build/outputs/apk/debug/app-debug.apk`.
