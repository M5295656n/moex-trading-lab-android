# Проверка календаря MOEX ISS

## Документированные методы

Публичная документация MOEX описывает календарные методы на хосте `iss.moex.com`:

- `/iss/calendars/stock` — торговые/неторговые дни фондового рынка, таблица `off_days`.
- `/iss/calendars/futures` — торговые/неторговые дни срочного рынка, таблица `off_days`.
- `/iss/calendars/stock/session` — расписание фондового рынка, таблица `session_schedule`.
- `/iss/calendars/futures/session` — расписание срочного рынка, таблица `session_schedule`.

Документация:
- https://www.moex.com/a8531
- https://www.moex.com/a2836
- https://www.moex.com/files/4qa0v882mt8xp7erfzs3bcjwxn
- https://moexalgo.github.io/docs/description/calendar-iss/

Отдельные методы `apim.moex.com/iss/calendars/...` в документации ALGOPACK показывают авторизацию Bearer и ответ 401 для неавторизованного запроса. Это не доказывает, что legacy `iss.moex.com` календарные методы требуют токен.

## Семантика

- `is_traded=0`: нет торгов.
- `is_traded=1`: торговый день.
- `is_traded=null`: отсутствие значения нельзя трактовать как выходной или торговый день.
- `reason`: H — holiday; W — weekend session; N — normal; T — transferred.
- В расписании фондового рынка `tradingsession`: 0 — утренняя, 1 — основная, 2 — вечерняя, 5 — ДСВД; -999 — не предусмотрено.
- В расписании срочного рынка `time_from/time_till` документированы как datetime, а фондового — как time.

## Статус live-проверки

Workflow `.github/workflows/moex-calendar-live-validation.yml` выполняет реальные HTTP-запросы к перечисленным методам и отдельно проверяет `apim.moex.com` без credentials.

Первый запуск остановился до HTTP-ответа на TLS-ошибке среды GitHub Actions (curl error 60: self-signed certificate in certificate chain). Временный диагностический вариант workflow использует `curl --insecure` только в smoke-проверке, чтобы выявить HTTP-код и структуру ответа. Это не должно переноситься в Android-клиент: TLS-проверка приложения остаётся включённой.

До получения и проверки успешных live-ответов календарь должен иметь статус UNKNOWN и не участвовать в выводах о пропусках свечей или актуальности данных.

## Известные точки проверки реализации

Текущий адаптер PR #10 вызывает endpoint без суффикса `.json`, а декодер выводит имя таблицы из последнего сегмента URL. При этом документация называет таблицы `off_days` и `session_schedule`, а не `stock`, `futures` или `session`. Нужно сверить эти места с фактическим JSON-ответом live smoke test до изменения production-кода.
