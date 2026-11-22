package de.nightdesk.trade

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PaperPersistenceTest {
    @Test fun orderIsIdempotentAndSurvivesReopeningRoom() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "paper-test-${UUID.randomUUID()}"
        fun open() = Room.databaseBuilder(context, PaperDb::class.java, name).build()
        val pair = PairInfo("BTC/EUR", "XBTEUR", 6, "0.0001".decimal(), "5".decimal())
        val quote = Quote("100".decimal(), "100.1".decimal(), "100".decimal(), "90000".decimal(), zero, Instant.now())
        try {
            withDatabase(open()) { db ->
                val paper = PaperBroker(db)
                paper.initialize()
                val account = checkNotNull(paper.dao.current())
                val order = preview(account.epoch, pair, true, "1".decimal(), quote)
                paper.execute(order, quote)
                paper.execute(order, quote)
                assertEquals(1, paper.dao.fills(account.epoch).size)
            }
            withDatabase(open()) { db ->
                val paper = PaperBroker(db)
                val account = checkNotNull(paper.dao.current())
                assertEquals(1, paper.dao.fills(account.epoch).size)
                paper.reset("220".decimal())
                val next = checkNotNull(paper.dao.current())
                assertEquals("220", next.initial)
                assertEquals(0, paper.dao.fills(next.epoch).size)
                assertEquals(1, paper.dao.fills(account.epoch).size)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
    private suspend fun withDatabase(db: PaperDb, check: suspend (PaperDb) -> Unit) {
        try {
            check(db)
        } finally {
            db.close()
        }
    }
}
