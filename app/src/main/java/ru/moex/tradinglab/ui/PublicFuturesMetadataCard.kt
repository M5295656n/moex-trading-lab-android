package ru.moex.tradinglab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.moex.tradinglab.data.local.MarketInstrumentEntity
import ru.moex.tradinglab.data.remote.MoexPublicFuturesCatalogRepository
import ru.moex.tradinglab.data.remote.PublicFuturesMetadata
import ru.moex.tradinglab.data.remote.PublicFuturesMetadataResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PublicFuturesMetadataCard(
    instrument: MarketInstrumentEntity,
    repository: MoexPublicFuturesCatalogRepository,
    modifier: Modifier = Modifier
) {
    var reloadToken by remember(instrument.instrumentId) { mutableStateOf(0) }
    var result by remember(instrument.instrumentId) { mutableStateOf<PublicFuturesMetadataResult?>(null) }
    var loading by remember(instrument.instrumentId) { mutableStateOf(false) }

    LaunchedEffect(instrument.secId, reloadToken) {
        loading = true
        result = repository.load(instrument.secId)
        loading = false
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("Публичные сведения о фьючерсе", style = MaterialTheme.typography.titleMedium)
            when {
                loading -> {
                    CircularProgressIndicator()
                    Text("Загружаем публичный каталог MOEX ISS…", style = MaterialTheme.typography.bodySmall)
                }
                result is PublicFuturesMetadataResult.Ready -> {
                    val metadata = (result as PublicFuturesMetadataResult.Ready).metadata
                    MetadataDetails(metadata)
                    OutlinedButton(
                        onClick = { reloadToken++ },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Обновить публичные сведения") }
                }
                result is PublicFuturesMetadataResult.Rejected -> {
                    Text(
                        (result as PublicFuturesMetadataResult.Rejected).reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { reloadToken++ },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Повторить загрузку каталога") }
                }
                else -> Unit
            }
            Text(
                "Это справочные данные публичного каталога. Они не подтверждают денежную стоимость шага и ГО; расчёт риска требует отдельной полной спецификации MOEX.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MetadataDetails(metadata: PublicFuturesMetadata) {
    Text("Контракт: ${metadata.secId}")
    metadata.shortName?.let { Text("Краткое название: $it") }
    metadata.fullName?.let { Text("Полное название: $it") }
    Text("Минимальный шаг (MINSTEP): ${metadata.minStep?.toString() ?: "не сообщён"}")
    Text("Точность (DECIMALS): ${metadata.decimals?.toString() ?: "не сообщена"}")
    Text("Предыдущая расчётная цена: ${metadata.previousSettlementPrice?.toString() ?: "не сообщена"}")
    Text("Последний день торгов: ${metadata.lastTradeDate?.toString() ?: "не сообщён"}")
    Text("Дата исполнения: ${metadata.lastDeliveryDate?.toString() ?: "не сообщена"}")
    Text("Источник: ${metadata.source}")
    Text(
        "Получено: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(metadata.fetchedAtEpochMillis))}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
