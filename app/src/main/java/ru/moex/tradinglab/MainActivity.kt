package ru.moex.tradinglab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.room.Room
import ru.moex.tradinglab.data.local.MarketDataStorage
import ru.moex.tradinglab.data.local.TradingLabDatabase
import ru.moex.tradinglab.data.remote.MoexIssClient
import ru.moex.tradinglab.data.remote.MoexIssDataRepository
import ru.moex.tradinglab.data.remote.MoexCalendarRepository
import ru.moex.tradinglab.data.remote.MoexFuturesSpecificationRepository
import ru.moex.tradinglab.data.remote.MoexPublicFuturesCatalogRepository
import ru.moex.tradinglab.data.remote.SecuritiesListingArchiveClient
import ru.moex.tradinglab.data.sync.MoexMarketDataSync
import ru.moex.tradinglab.data.sync.SecuritiesListingSync
import ru.moex.tradinglab.ui.BacktestScreen
import ru.moex.tradinglab.ui.ChartScreen
import ru.moex.tradinglab.ui.MarketDataDiagnosticsScreen
import ru.moex.tradinglab.ui.theme.MoexTradingLabTheme

private enum class MainTab(val title: String) {
    CHART("График"),
    BACKTEST("Тест"),
    DATA("Данные")
}

class MainActivity : ComponentActivity() {
    private val moexIssClient by lazy { MoexIssClient() }

    private val calendarRepository by lazy { MoexCalendarRepository(moexIssClient) }

    private val futuresSpecifications by lazy {
        MoexFuturesSpecificationRepository(moexIssClient)
    }

    private val publicFuturesCatalog by lazy {
        MoexPublicFuturesCatalogRepository(moexIssClient)
    }

    private val database by lazy {
        Room.databaseBuilder(
            applicationContext,
            TradingLabDatabase::class.java,
            "moex-trading-lab.db"
        )
            .addMigrations(
                TradingLabDatabase.MIGRATION_1_2,
                TradingLabDatabase.MIGRATION_2_3,
                TradingLabDatabase.MIGRATION_3_4
            )
            .build()
    }

    private val securitiesListingSync by lazy {
        SecuritiesListingSync(
            archiveSource = SecuritiesListingArchiveClient(),
            marketDataDao = database.marketDataDao(),
            securitiesListingDao = database.securitiesListingDao()
        )
    }

    private val marketDataSync by lazy {
        MoexMarketDataSync(
            remote = MoexIssDataRepository(moexIssClient),
            storage = MarketDataStorage(database.marketDataDao())
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoexTradingLabTheme {
                TradingLabRoot(
                    database = database,
                    sync = marketDataSync,
                    calendar = calendarRepository,
                    futuresSpecifications = futuresSpecifications,
                    publicFuturesCatalog = publicFuturesCatalog,
                    securitiesListingSync = securitiesListingSync
                )
            }
        }
    }
}

@Composable
private fun TradingLabRoot(
    database: TradingLabDatabase,
    sync: MoexMarketDataSync,
    calendar: MoexCalendarRepository,
    futuresSpecifications: MoexFuturesSpecificationRepository,
    publicFuturesCatalog: MoexPublicFuturesCatalogRepository,
    securitiesListingSync: SecuritiesListingSync
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(MainTab.CHART.ordinal) }
    val dao = database.marketDataDao()

    MaterialTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    MainTab.entries.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                Text(
                                    when (tab) {
                                        MainTab.CHART -> "▥"
                                        MainTab.BACKTEST -> "◷"
                                        MainTab.DATA -> "☷"
                                    }
                                )
                            },
                            label = { Text(tab.title) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                ) {
                    when (MainTab.entries[selectedTab]) {
                        MainTab.CHART -> ChartScreen(
                            dao = dao,
                            sync = sync,
                            futuresSpecifications = futuresSpecifications,
                            publicFuturesCatalog = publicFuturesCatalog,
                            onOpenDiagnostics = { selectedTab = MainTab.DATA.ordinal }
                        )
                        MainTab.BACKTEST -> BacktestScreen(
                            dao = dao,
                            sync = sync,
                            calendar = calendar
                        )
                        MainTab.DATA -> MarketDataDiagnosticsScreen(
                            dao = dao,
                            sync = sync,
                            securitiesListingDao = database.securitiesListingDao(),
                            securitiesListingSync = securitiesListingSync
                        )
                    }
                }
            }
        }
    }
}