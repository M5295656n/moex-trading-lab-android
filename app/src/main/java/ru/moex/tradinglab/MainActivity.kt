package ru.moex.tradinglab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.room.Room
import ru.moex.tradinglab.data.local.MarketDataStorage
import ru.moex.tradinglab.data.local.TradingLabDatabase
import ru.moex.tradinglab.data.remote.MoexIssClient
import ru.moex.tradinglab.data.remote.MoexIssDataRepository
import ru.moex.tradinglab.data.sync.MoexMarketDataSync
import ru.moex.tradinglab.ui.MarketDataDiagnosticsScreen
import ru.moex.tradinglab.ui.theme.MoexTradingLabTheme

class MainActivity : ComponentActivity() {
    private val database by lazy {
        Room.databaseBuilder(
            applicationContext,
            TradingLabDatabase::class.java,
            "moex-trading-lab.db"
        )
            .addMigrations(
                TradingLabDatabase.MIGRATION_1_2,
                TradingLabDatabase.MIGRATION_2_3
            )
            .build()
    }

    private val marketDataSync by lazy {
        MoexMarketDataSync(
            remote = MoexIssDataRepository(MoexIssClient()),
            storage = MarketDataStorage(database.marketDataDao())
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoexTradingLabTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MarketDataDiagnosticsScreen(
                        dao = database.marketDataDao(),
                        sync = marketDataSync
                    )
                }
            }
        }
    }
}
