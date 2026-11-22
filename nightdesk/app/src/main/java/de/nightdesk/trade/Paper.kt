package de.nightdesk.trade

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

@Entity internal data class PaperAccount(@PrimaryKey val id: Int = 1, val initial: String, val epoch: String)
@Entity internal data class PaperFill(
    @PrimaryKey val id: String, val epoch: String, val at: Long, val symbol: String, val buy: Boolean,
    val qty: String, val price: String, val fee: String, val strategy: String
)
@Entity(primaryKeys = ["epoch", "at"])
internal data class PaperMark(val epoch: String, val at: Long, val equity: String)
@Entity internal data class Watch(@PrimaryKey val symbol: String)
@Dao internal interface PaperDao {
    @Query("SELECT * FROM PaperAccount WHERE id = 1") fun account(): kotlinx.coroutines.flow.Flow<PaperAccount?>
    @Query("SELECT * FROM PaperAccount WHERE id = 1") suspend fun current(): PaperAccount?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(account: PaperAccount)
    @Query("SELECT * FROM PaperFill ORDER BY at, rowid") fun history(): kotlinx.coroutines.flow.Flow<List<PaperFill>>
    @Query("SELECT * FROM PaperFill WHERE epoch = :epoch ORDER BY at, rowid") suspend fun fills(epoch: String): List<PaperFill>
    @Insert suspend fun insert(fill: PaperFill)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun mark(mark: PaperMark)
    @Query("SELECT * FROM PaperMark ORDER BY at") fun marks(): kotlinx.coroutines.flow.Flow<List<PaperMark>>
    @Query("SELECT * FROM Watch") fun watchlist(): kotlinx.coroutines.flow.Flow<List<Watch>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun watch(item: Watch)
    @Query("DELETE FROM Watch WHERE symbol = :symbol") suspend fun unwatch(symbol: String)
}
@Database(entities = [PaperAccount::class, PaperFill::class, PaperMark::class, Watch::class], version = 1, exportSchema = false)
internal abstract class PaperDb : RoomDatabase() {
    abstract fun dao(): PaperDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, PaperDb::class.java, "nightdesk-paper.db").build()
    }
}

internal data class Holding(val quantity: BigDecimal, val basis: BigDecimal)
internal data class PaperPortfolio(
    val cash: BigDecimal, val holdings: Map<String, Holding>, val fees: BigDecimal, val results: List<Pair<String, BigDecimal>>
) {
    fun value(quotes: Map<String, Quote>, now: Instant = Instant.now()): BigDecimal? {
        var equity = cash
        for ((symbol, position) in holdings) {
            val quote = quotes[symbol]?.takeIf { it.isCurrent(now) } ?: return null
            equity += position.quantity * quote.bid
        }
        return equity
    }
}

internal fun portfolio(initial: BigDecimal, fills: List<PaperFill>): PaperPortfolio {
    var cash = initial
    var fees = zero
    val holdings = mutableMapOf<String, Holding>()
    val outcomes = mutableListOf<Pair<String, BigDecimal>>()
    for (fill in fills) {
        val quantity = fill.qty.decimal()
        val cost = fill.price.decimal() * quantity
        val fee = fill.fee.decimal()
        fees += fee
        val held = holdings[fill.symbol] ?: Holding(zero, zero)
        if (fill.buy) {
            cash -= cost + fee
            holdings[fill.symbol] = Holding(held.quantity + quantity, held.basis + cost + fee)
        } else {
            check(held.quantity >= quantity && quantity > zero)
            val basis = if (quantity == held.quantity) held.basis else held.basis * quantity.divide(held.quantity, precision)
            cash += cost - fee
            outcomes += fill.symbol to (cost - fee - basis)
            if (held.quantity == quantity) holdings.remove(fill.symbol)
            else holdings[fill.symbol] = Holding(held.quantity - quantity, held.basis - basis)
        }
    }
    return PaperPortfolio(cash, holdings, fees, outcomes)
}

internal data class PaperPreview(val id: String, val epoch: String, val pair: PairInfo, val buy: Boolean,
                                 val requested: BigDecimal, val quantity: BigDecimal, val price: BigDecimal, val fee: BigDecimal,
                                 val created: Instant) {
    val gross: BigDecimal get() = quantity * price
}

internal fun preview(epoch: String, pair: PairInfo, buy: Boolean, quantity: BigDecimal, quote: Quote,
                     now: Instant = Instant.now()): PaperPreview {
    require(quote.isCurrent(now)) { "Aktueller Kurs fehlt" }
    require(quote.spread <= "0.01".decimal()) { "Spread über 1 %" }
    require(quote.volumeEur >= "20000".decimal()) { "24-Stunden-Volumen unter 20.000 €" }
    require(quantity.scale() <= pair.quantityDecimals && quantity >= pair.minimum) { "Menge oder Dezimalstellen unter Börsengrenze" }
    val price = if (buy) quote.ask * "1.001".decimal() else quote.bid * "0.999".decimal()
    val depth = (if (buy) quote.askQuantity else quote.bidQuantity).setScale(pair.quantityDecimals, RoundingMode.DOWN)
    val filled = minOf(quantity, depth)
    require(filled > zero) { "Keine Liquidität zum angezeigten Kurs" }
    val gross = price * filled
    require(quantity * price >= pair.minCost) { "Mindestorderwert nicht erreicht" }
    require(quantity * price <= "100000".decimal()) { "Simulationslimit von 100.000 € je Order" }
    val fee = (gross * "0.004".decimal()).setScale(8, RoundingMode.UP)
    return PaperPreview(UUID.randomUUID().toString(), epoch, pair, buy, quantity, filled, price, fee, now)
}

internal class PaperBroker(private val db: PaperDb) {
    val dao = db.dao()
    suspend fun initialize() = db.withTransaction {
        if (dao.current() == null) dao.save(PaperAccount(initial = "1000", epoch = UUID.randomUUID().toString()))
    }
    suspend fun reset(amount: BigDecimal) = db.withTransaction {
        require(amount >= BigDecimal.ONE && amount <= "1000000".decimal() && amount.scale() <= 2) {
            "Startkapital zwischen 1 und 1.000.000 € eingeben"
        }
        dao.save(PaperAccount(initial = amount.toPlainString(), epoch = UUID.randomUUID().toString()))
    }
    suspend fun execute(order: PaperPreview, fresh: Quote, strategy: String = "Manuell") = db.withTransaction {
        val account = checkNotNull(dao.current())
        require(account.epoch == order.epoch && order.created.isAfter(Instant.now().minusSeconds(30))) {
            "Vorschau abgelaufen"
        }
        val fills = dao.fills(account.epoch)
        if (fills.any { it.id == order.id }) return@withTransaction
        val updated = preview(order.epoch, order.pair, order.buy, order.requested, fresh)
        require(if (order.buy) updated.price <= order.price else updated.price >= order.price) {
            "Kurs hat sich verschlechtert. Neue Vorschau erstellen."
        }
        val balance = portfolio(account.initial.decimal(), fills)
        require(updated.quantity <= order.quantity) { "Liquidität hat zugenommen. Neue Vorschau für größere Ausführung erstellen." }
        if (strategy != "Manuell" && order.buy) {
            require(order.pair.symbol !in balance.holdings && balance.holdings.size < 3) { "Papierbot-Positionslimit erreicht" }
        }
        if (order.buy) require(balance.cash >= updated.gross + updated.fee) { "Virtuelles EUR-Guthaben reicht nicht" }
        else require((balance.holdings[order.pair.symbol]?.quantity ?: zero) >= order.requested) { "Virtuelle Position reicht nicht" }
        dao.insert(PaperFill(order.id, order.epoch, System.currentTimeMillis(), order.pair.symbol,
            order.buy, updated.quantity.toPlainString(), updated.price.toPlainString(), updated.fee.toPlainString(), strategy))
    }

    suspend fun sample(quotes: Map<String, Quote>) = db.withTransaction {
        val account = dao.current() ?: return@withTransaction
        val equity = portfolio(account.initial.decimal(), dao.fills(account.epoch)).value(quotes) ?: return@withTransaction
        dao.mark(PaperMark(account.epoch, System.currentTimeMillis() / 60_000 * 60_000, equity.toPlainString()))
    }
}
