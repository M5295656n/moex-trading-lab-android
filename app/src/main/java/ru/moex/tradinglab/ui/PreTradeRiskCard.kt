package ru.moex.tradinglab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.moex.tradinglab.analysis.PreTradeRiskCalculator
import ru.moex.tradinglab.analysis.PreTradeRiskInput
import ru.moex.tradinglab.analysis.PreTradeRiskResult
import ru.moex.tradinglab.analysis.RiskInstrumentType
import ru.moex.tradinglab.analysis.TradeDirection
import ru.moex.tradinglab.data.local.MarketInstrumentEntity
import java.time.ZoneId

/**
 * Equity-only UI adapter for the deterministic pre-trade calculator.
 * Futures stay blocked until a verified, fresh MOEX contract specification
 * and the broker's per-contract fee are supplied by a dedicated data source.
 */
@Composable
fun PreTradeRiskCard(
    instrument: MarketInstrumentEntity,
    modifier: Modifier = Modifier
) {
    var capital by remember(instrument.instrumentId) { mutableStateOf("1000") }
    var riskPercent by remember(instrument.instrumentId) { mutableStateOf("2") }
    var entry by remember(instrument.instrumentId) { mutableStateOf("") }
    var stop by remember(instrument.instrumentId) { mutableStateOf("") }
    var target by remember(instrument.instrumentId) { mutableStateOf("") }
    var commissionPercent by remember(instrument.instrumentId) { mutableStateOf("0.05") }
    var slippageBps by remember(instrument.instrumentId) { mutableStateOf("5") }
    var result by remember(instrument.instrumentId) { mutableStateOf<PreTradeRiskResult?>(null) }
    var validationMessage by remember(instrument.instrumentId) { mutableStateOf<String?>(null) }

    val isEquity = instrument.market == "SHARES"
    val lotValue = instrument.lotSize
    val lotSize = lotValue?.takeIf {
        it.isFinite() && it > 0.0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0
    }?.toInt()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Предторговый риск", style = MaterialTheme.typography.titleLarge)
            Text(
                "${instrument.secId} · расчёт только для оценки, без отправки брокерских заявок.",
                style = MaterialTheme.typography.bodySmall
            )

            when {
                instrument.market == "FUTURES" -> {
                    Text(
                        "Расчёт фьючерса пока заблокирован: не подключён проверенный источник спецификации MOEX (шаг цены, стоимость шага, ГО, экспирация) и комиссии брокера за контракт. Ручные или условные значения не используются.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                !isEquity -> {
                    Text(
                        "Для индексов расчёт размера позиции недоступен: индекс не является торгуемой акцией.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                lotSize == null -> {
                    Text(
                        "В каталоге MOEX отсутствует корректный целочисленный размер лота. Расчёт заблокирован до получения достоверного значения.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> {
                    Text("Биржевой лот: $lotSize", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = capital,
                        onValueChange = { capital = it; result = null; validationMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Доступный капитал, ₽") }
                    )
                    OutlinedTextField(
                        value = riskPercent,
                        onValueChange = { riskPercent = it; result = null; validationMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Риск на сделку, %") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = entry,
                            onValueChange = { entry = it; result = null; validationMessage = null },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Вход, ₽") }
                        )
                        OutlinedTextField(
                            value = stop,
                            onValueChange = { stop = it; result = null; validationMessage = null },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Стоп, ₽") }
                        )
                    }
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it; result = null; validationMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Цель, ₽") }
                    )
                    OutlinedTextField(
                        value = commissionPercent,
                        onValueChange = { commissionPercent = it; result = null; validationMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Комиссия за сторону, % (проверьте тариф брокера)") }
                    )
                    OutlinedTextField(
                        value = slippageBps,
                        onValueChange = { slippageBps = it; result = null; validationMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Проскальзывание за сторону, б.п.") }
                    )
                    Text(
                        "Начальные значения комиссии 0,05% и проскальзывания 5 б.п. — редактируемые допущения, а не данные брокера или биржи.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            val parsedCapital = capital.toRiskNumber()
                            val parsedRisk = riskPercent.toRiskNumber()
                            val parsedEntry = entry.toRiskNumber()
                            val parsedStop = stop.toRiskNumber()
                            val parsedTarget = target.toRiskNumber()
                            val parsedCommission = commissionPercent.toRiskNumber()
                            val parsedSlippage = slippageBps.toRiskNumber()
                            val values = listOf(
                                parsedCapital, parsedRisk, parsedEntry, parsedStop,
                                parsedTarget, parsedCommission, parsedSlippage
                            )
                            if (values.any { it == null }) {
                                validationMessage = "Заполните все поля корректными числами. Можно использовать запятую или точку."
                                result = null
                            } else {
                                val now = System.currentTimeMillis()
                                result = PreTradeRiskCalculator.calculate(
                                    PreTradeRiskInput(
                                        capital = parsedCapital!!,
                                        riskFraction = parsedRisk!! / 100.0,
                                        instrumentType = RiskInstrumentType.EQUITY,
                                        direction = TradeDirection.LONG,
                                        entryPrice = parsedEntry!!,
                                        stopPrice = parsedStop!!,
                                        targetPrice = parsedTarget!!,
                                        lotSize = lotSize,
                                        commissionRatePerSide = parsedCommission!! / 100.0,
                                        slippageBasisPointsPerSide = parsedSlippage!!,
                                        asOfDate = java.time.Instant.ofEpochMilli(now)
                                            .atZone(ZoneId.of("Europe/Moscow"))
                                            .toLocalDate()
                                            .toString()
                                    )
                                )
                                validationMessage = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Рассчитать размер позиции")
                    }
                    validationMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    when (val calculation = result) {
                        is PreTradeRiskResult.Ready -> {
                            Text("Количество: ${calculation.quantity} акций", style = MaterialTheme.typography.titleMedium)
                            Text("Бюджет риска: ${calculation.riskBudget.moneyLabel()} ₽")
                            Text("Оценка убытка до стопа: ${calculation.estimatedMaximumLoss.moneyLabel()} ₽")
                            Text("Потенциальный результат после издержек: ${calculation.estimatedNetReward.moneyLabel()} ₽")
                            Text("Соотношение доходности к риску: ${calculation.rewardRiskRatio.moneyLabel()}")
                            Text("Сумма входа: ${calculation.entryNotional.moneyLabel()} ₽")
                            Text("Требуемый капитал: ${calculation.requiredCapital.moneyLabel()} ₽")
                            Text("Комиссия вход + стоп: ${calculation.estimatedRoundTripCommission.moneyLabel()} ₽")
                            Text("Проскальзывание вход + стоп: ${calculation.estimatedRoundTripSlippage.moneyLabel()} ₽")
                            Text(
                                "Оценка предполагает исполнение около стоп-цены. При гэпе или низкой ликвидности фактический убыток может быть выше.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is PreTradeRiskResult.Rejected -> {
                            Text(
                                "Расчёт отклонён: ${calculation.reason}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        null -> Unit
                    }
                }
            }
        }
    }
}

private fun String.toRiskNumber(): Double? =
    trim().replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)

private fun Double.moneyLabel(): String = String.format(java.util.Locale("ru", "RU"), "%.2f", this)
