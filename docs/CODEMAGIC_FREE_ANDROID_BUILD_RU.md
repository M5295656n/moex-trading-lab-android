# Бесплатная альтернативная сборка Android APK через Codemagic

## Зачем

GitHub Actions в текущем аккаунте не запускает jobs из-за billing restriction. Чтобы не менять приватность GitHub-репозитория и не покупать подписку, предусмотрен отдельный путь через Codemagic Personal Free.

Официальные условия Codemagic: личный бесплатный план предоставляет 500 минут сборки в месяц на macOS M2; Linux/Windows не входят в бесплатные минуты. Поэтому workflow использует `mac_mini_m2` и собирает Android APK на macOS.

## Подключение один раз

1. Создать личный аккаунт на https://codemagic.io/signup (не Team).
2. Подключить GitHub и разрешить Codemagic доступ только к репозиторию `M5295656N/moex-trading-lab-android`.
3. Добавить приложение из этого репозитория.
4. Выбрать ветку `ci/codemagic-free-android-build-2026-11-25`, просканировать конфигурацию `codemagic.yaml`.
5. В Application settings → Environment variables создать группу `moex_signing`.
6. Добавить пять переменных из собственного защищённого пакета постоянного ключа. Все секреты пометить Secret:
   - `MOEX_SIGNING_KEYSTORE_BASE64` — Base64 JKS одной строкой;
   - `MOEX_SIGNING_STORE_PASSWORD`;
   - `MOEX_SIGNING_KEY_ALIAS`;
   - `MOEX_SIGNING_KEY_PASSWORD`;
   - `MOEX_SIGNING_CERT_SHA256` — SHA-256 сертификата без двоеточий.
7. Запустить workflow `MOEX Trading Lab — Android Release`.

## Что проверяет workflow

- Java 17, Android SDK 36 и Gradle 8.13;
- unit tests и компиляцию Android instrumentation tests;
- Debug и Release APK с постоянным ключом MOEX Trading Lab;
- подпись APK и точное совпадение SHA-256 сертификата;
- applicationId `ru.moex.tradinglab`, versionCode `1`, versionName `1.0.0`.

Оба APK публикуются как артефакты Codemagic. APK можно передавать пользователю только после успешного workflow и проверки артефакта.

## Ограничения и безопасность

- Не загружать JKS и пароли в Git, Issues, PR или сообщения.
- Не создавать новый signing key. Если исходный JKS и его пароли не сохранены, выпуск совместимого APK остановить до восстановления исходного ключа.
- Бесплатные 500 минут действуют на личном аккаунте и обновляются ежемесячно; не включать платный billing.
- Этот workflow не запускает Android Emulator instrumentation tests; он запускает unit tests и компиляцию instrumentation tests.
- GitHub Actions остаётся без изменений; репозиторий остаётся приватным.
