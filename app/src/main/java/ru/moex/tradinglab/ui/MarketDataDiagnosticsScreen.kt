package ru.moex.tradinglab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.moex.tradinglab.data.local.CatalogSyncEntity
import ru.moex.tradinglab.data.local.MarketDataDao
import ru.moex.tradinglab.data.remote.MoexMarket
import ru.moex.tradinglab.data.sync.MoexMarketDataSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MarketDataDiagnosticsScreen(
    dao: MarketDataDao,
    sync: MoexMarketDataSync
) {
    val instruments by dao.observeInstruments().collectAsState(initial = emptyList())
    val catalogStates by dao.observeCatalogSync().collectAsState(initial = emptyList())
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val messages = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    suspend fun performCatalogLoad(market: MoexMarket) {
        if (busy[market.name] == true) return
        busy[market.name] = true
        messages.remove(market.name)
        try {
            val result = sync.syncCatalog(market)
            messages[market.name] = "Получено инструментов: ${result.instruments.size}. Страниц: ${result.pageCount}."
        } catch (error: Exception) {
            messages[market.name] = "Ошибка загрузки: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            busy[market.name] = false
        }
    }

    fun loadCatalog(market: MoexMarket) {
        scope.launch { performCatalogLoad(market) }
    }

    fun loadAllCatalogs() {
        scope.launch {
            MoexMarket.entries.forEach { market -> performCatalogLoad(market) }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "MOEX Trading Lab",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Диагностика рыночных данных",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "Загрузка каталогов выполняется только на чтение. Полученные записи сохраняются локально в Room.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedButton(
                    onClick = {
                        loadAllCatalogs()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Text("Обновить все каталоги")
                }
            }

            items(MoexMarket.entries.toList(), key = { it.name }) { market ->
                val saved = catalogStates.firstOrNull { it.market == market.name }
                val count = instruments.count { it.market == market.name }
                MarketCatalogCard(
                    market = market,
                    saved = saved,
                    instrumentCount = count,
                    isLoading = busy[market.name] == true,
                    message = messages[market.name],
                    onLoad = { loadCatalog(market) }
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Что означает полнота", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "«Полная» означает, что ISS вернул страницу короче запрошенного размера. Достижение лимита страниц или остановка пагинации не считаются полной историей.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            text = "Успешная загрузка каталога не доказывает доступность свечей для каждого инструмента и таймфрейма. Эти данные проверяются отдельно.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Источник котировочных справочников: MOEX ISS · автоматическая отправка заявок не используется.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MarketCatalogCard(
    market: MoexMarket,
    saved: CatalogSyncEntity?,
    instrumentCount: Int,
    isLoading: Boolean,
    message: String?,
    onLoad: () -> Unit
) {
    val title = when (market) {
        MoexMarket.SHARES -> "Акции"
        MoexMarket.FUTURES -> "Фьючерсы"
        MoexMarket.INDICES -> "Индексы"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "В Room: $instrumentCount",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(onClick = onLoad, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Загрузить")
                    }
                }
            }
            if (saved == null) {
                Text(
                    text = "Каталог ещё не загружался",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
            } else {
                Text("Источник: ${saved.source}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                Text("Получено записей: ${saved.instrumentCount}", style = MaterialTheme.typography.bodySmall)
                Text("Страниц: ${saved.pageCount} · размер страницы: ${saved.pageSize}", style = MaterialTheme.typography.bodySmall)
                Text("Пропущено некорректных строк: ${saved.skippedRows}", style = MaterialTheme.typography.bodySmall)
                Text("Полнота: ${completenessLabel(saved.completeness)}", style = MaterialTheme.typography.bodySmall)
                Text("Последнее получение: ${formatTimestamp(saved.fetchedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
            }
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private fun completenessLabel(value: String): String = when (value) {
    "COMPLETE" -> "получена короткая завершающая страница"
    "PAGE_LIMIT_REACHED" -> "достигнут лимит страниц"
    "PAGINATION_STALLED" -> "пагинация остановилась"
    else -> value
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
