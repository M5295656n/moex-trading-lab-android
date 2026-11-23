# Проверка календаря MOEX ISS

## Документированные методы

Публичная документация MOEX описывает календарные методы:

- `/iss/calendars/stock` — календарь фондового рынка, таблица `off_days`.
- `/iss/calendars/futures` — календарь срочного рынка, таблица `off_days`.
- `/iss/calendars/stock/session` — расписание фондового рынка, таблица `session_schedule`.
- `/iss/calendars/futures/session` — расписание срочного рынка, таблица `session_schedule`.

Документация:
- https://www.moex.com/a8531
- https://www.moex.com/a2836
- https://www.moex.com/files/4qa0v882mt8xp7erfzs3bcjwxn
- https://moexalgo.github.io/docs/description/calendar-iss/

## Результаты live smoke test (23.11.2026, GitHub Actions)

Проверки выполнялись с отключённой проверкой TLS только в диагностическом runner: runner получал self-signed certificate in certificate chain (curl error 60). Это не должно переноситься в Android-клиент.

| Endpoint | HTTP / Content-Type | Результат |
|---|---|---|
| `/iss/engines/stock.json` | 200 / application/json | Доступен |
| `/iss/engines/futures.json` | 200 / application/json | Доступен |
| `/iss/calendars/stock[.json]` | 200 / text/html | Не календарный JSON |
| `/iss/calendars/futures[.json]` | 200 / text/html | Не календарный JSON |
| `/iss/calendars/stock/session[.json]` | 200 / text/html | Не расписание JSON |
| `/iss/calendars/futures/session[.json]` | 200 / text/html | Не расписание JSON |
| `apim.moex.com/iss/calendars/stock.json` без токена | 401 | Требуется авторизация |

Контрольный endpoint `engines/stock.json` подтвердил, что сам публичный ISS host отвечает JSON. Следовательно, HTML на `calendars/*` не объясняется общей недоступностью хоста. Причина может быть в отдельном ограничении/маршрутизации сервиса; считать эти ответы доступным календарём нельзя.

## Что можно получить бесплатно через engine endpoints

Live-ответы `engines/{stock,futures}.json` содержат:

- `timetable`: недельное расписание из 7 строк (`week_day`, `is_work_day`, `start_time`, `stop_time`);
- `dailytable`: исключения по датам (на момент проверки 82 строки для stock и 72 для futures);
- для futures дополнительно `session_schedule` и `session_schedule.types` (13 строк расписания в live-ответе на момент проверки).

Примеры полей live-ответа:
- `dailytable`: `date`, `is_work_day`, `start_time`, `stop_time`;
- `timetable`: `week_day`, `is_work_day`, `start_time`, `stop_time`;
- futures `session_schedule`: `trade_session_date`, `boardid`, `secid`, `type`, `time_from`, `time_till`, `updatetime`.

Эти engine-данные пригодны для ограниченного общего календарного слоя, но не подтверждают полноту расписания конкретного инструмента/борда/сессии на всю историю. Недельный engine timetable не заменяет board/security-specific расписание, а futures session_schedule из этого ответа не является доказанной полной исторической таблицей. Нельзя на их основании автоматически объявлять все свечные пропуски реальными.

## Семантика официального календаря

- `is_traded=0`: нет торгов.
- `is_traded=1`: торговый день.
- `is_traded=null`: отсутствие значения нельзя трактовать как выходной или торговый день.
- `reason`: H — holiday; W — weekend session; N — normal; T — transferred.
- В расписании фондового рынка `tradingsession`: 0 — утренняя, 1 — основная, 2 — вечерняя, 5 — ДСВД; -999 — не предусмотрено.
- В расписании срочного рынка `time_from/time_till` документированы как datetime, а фондового — как time.

## Известные точки проверки реализации

Текущий адаптер PR #10 вызывает endpoint без суффикса `.json`, а декодер выводит имя таблицы из последнего сегмента URL. Документация называет таблицы `off_days` и `session_schedule`, а не `stock`, `futures` или `session`. Live-проверка не подтвердила JSON на этих calendar endpoints, поэтому production-адаптер нельзя включать до решения вопроса доступа и исправления table mapping.

## Текущее безопасное поведение

Календарь и expected candle schedule остаются UNKNOWN для оценки полноты свечей, пока нет проверенного расписания для конкретного рынка/борда/инструмента/таймфрейма. Не подменять отсутствие календарных данных нулевыми пропусками, общим расписанием или предположительными торговыми часами.
