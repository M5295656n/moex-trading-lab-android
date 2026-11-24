# Постоянная подпись Android Release APK

## Зачем

Все устанавливаемые обновления приложения `ru.moex.tradinglab` должны подписываться одним и тем же приватным ключом. Debug APK из CI предназначен только для диагностики и не является установочным релизом.

Текущая версия: `versionName=1.0.1`, `versionCode=2`. Идентификатор приложения сохранён: `ru.moex.tradinglab`.

## Используем существующий ключ MyCapital

Новый ключ создавать не нужно. Для MOEX Trading Lab используется тот же файл `my-capital-signing.jks`, который уже применяется в сборках MyCapital.

Контрольный SHA-256 сертификата:
`77FE78460534AC46DA9A42B03435DEF8CC5D2D6D3CC67C7D3C6C8FD35008FD66`.

В MyCapital текущая конфигурация использует следующие GitHub Secrets:
- `ANDROID_KEYSTORE_BASE64` — Base64 содержимого JKS;
- `ANDROID_KEYSTORE_PASSWORD` — пароль хранилища;
- `ANDROID_KEY_ALIAS` — alias;
- `ANDROID_KEY_PASSWORD` — пароль приватного ключа.

GitHub не показывает сохранённые значения секретов повторно. Для MOEX Trading Lab нужно создать отдельные repository secrets с префиксом `MOEX_RELEASE_`, используя те же значения. Не пытайтесь получить пароли из логов и не публикуйте их.

## Подготовка четырёх секретов MOEX Trading Lab

Откройте репозиторий **M5295656N/moex-trading-lab-android** → **Settings → Secrets and variables → Actions → New repository secret**.

Создайте:

- `MOEX_RELEASE_KEYSTORE_BASE64` — Base64 существующего файла `my-capital-signing.jks`;
- `MOEX_RELEASE_STORE_PASSWORD` — то же значение, что `ANDROID_KEYSTORE_PASSWORD` в MyCapital;
- `MOEX_RELEASE_KEY_ALIAS` — то же значение, что `ANDROID_KEY_ALIAS` в MyCapital;
- `MOEX_RELEASE_KEY_PASSWORD` — то же значение, что `ANDROID_KEY_PASSWORD` в MyCapital.

Для получения Base64 из локального файла в PowerShell выполните:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("ПОЛНЫЙ_ПУТЬ_К_ФАЙЛУ\\my-capital-signing.jks"))
```

Скопируйте результат непосредственно в значение секрета `MOEX_RELEASE_KEYSTORE_BASE64`. Не вставляйте его в чат, Issue или Pull Request.

Пароли и alias нельзя восстановить из Base64-файла или из SHA-256 отпечатка сертификата. Если они не сохранены в менеджере паролей или других личных записях, остановитесь: не создавайте новый ключ и не меняйте настройки подписи MyCapital.

## Сборка и проверка

После настройки четырёх секретов GitHub Actions:
1. Восстановит JKS во временный каталог runner.
2. Соберёт Release APK с существующим ключом MyCapital.
3. Проверит подпись, package name, versionCode и versionName.
4. Сверит SHA-256 сертификата с контрольным отпечатком выше.
5. Опубликует артефакт `moex-trading-lab-release-v1.0.1`.

Debug APK с именем `moex-trading-lab-debug-diagnostics` не устанавливайте поверх Release и не используйте для обновления.

## Важное ограничение для первой установки Release

Ранее выданный CI Debug APK подписан другим временным ключом. Android не позволит обновить его Release APK, даже если Release использует общий ключ MyCapital. Перед удалением старого приложения сохраните нужные данные: удаление приложения обычно удаляет и локальную базу Room.

В дальнейшем все релизные сборки MOEX Trading Lab должны использовать исходный JKS и тот же сертификат. Не генерируйте новый ключ и не заменяйте секреты другим файлом.
