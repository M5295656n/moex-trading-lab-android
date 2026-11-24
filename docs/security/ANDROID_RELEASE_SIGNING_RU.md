# Постоянная подпись Android Release APK

## Зачем

Все устанавливаемые обновления приложения `ru.moex.tradinglab` должны быть подписаны одним и тем же приватным ключом. Debug APK из CI предназначен только для диагностики и не является установочным релизом.

Текущая версия после исправления: `versionName=1.0.1`, `versionCode=2`. Идентификатор приложения сохранён: `ru.moex.tradinglab`.

## Однократное создание ключа

Создайте отдельный каталог для ключа вне Git-репозитория и выполните команду:

```bash
keytool -genkeypair -v \
  -keystore moex-trading-lab-release.jks \
  -alias moex-trading-lab \
  -keyalg RSA -keysize 4096 -validity 10000
```

Задайте надёжные пароли. Не отправляйте их в чат, не добавляйте файл JKS в Git и не храните единственную копию на телефоне. Сделайте минимум две резервные копии JKS в защищённых местах. Потеря ключа лишит возможности выпускать обновления поверх установленной версии.

## Настройка GitHub Actions secrets

В репозитории откройте **Settings → Secrets and variables → Actions → New repository secret** и создайте четыре секрета:

- `MOEX_RELEASE_KEYSTORE_BASE64` — содержимое JKS, закодированное Base64 одной строкой.
- `MOEX_RELEASE_STORE_PASSWORD` — пароль хранилища.
- `MOEX_RELEASE_KEY_ALIAS` — `moex-trading-lab` (или фактически выбранный alias).
- `MOEX_RELEASE_KEY_PASSWORD` — пароль приватного ключа.

Linux/macOS: получить Base64 можно командой `base64 -w 0 moex-trading-lab-release.jks` (на macOS используйте `base64 < moex-trading-lab-release.jks | tr -d '\\n'`).

PowerShell:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("moex-trading-lab-release.jks"))
```

Вставьте полученную строку только в значение секрета GitHub. Не публикуйте её в Issues, Pull Requests или чате.

## Сборка и проверка

После добавления всех четырёх secrets GitHub Actions автоматически:
1. Восстановит JKS во временный каталог runner.
2. Соберёт Release APK с постоянной подписью.
3. Проверит подпись, package name, versionCode и versionName.
4. Опубликует артефакт `moex-trading-lab-release-v1.0.1`.

Debug APK с именем `moex-trading-lab-debug-diagnostics` не устанавливайте поверх Release и не используйте для обновления.

## Важное ограничение для первой переустановки

Новый ключ будет новым ключом подписи: прежний CI debug keystore не был сохранён. Поэтому уже установленную сборку Android не позволит обновить новым APK. Пользователь подтвердил, что удалит старую установку. Удаление приложения обычно удаляет и его локальную базу Room; перед удалением следует сохранить нужные данные, если в приложении появится экспорт.

В дальнейшем нельзя генерировать новый ключ или менять secrets на другой JKS. Все релизные сборки должны использовать исходный keystore.
