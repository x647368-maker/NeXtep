package io.github.x647368maker.atomicworkbench.display

import java.util.ArrayDeque
import kotlin.random.Random

fun main() {
    val random = Random(20261110)
    val total = 5000
    repeat(total) { trial ->
        var mainDisplay = 0
        var slotDisplay = 7
        var promotions = 0
        var demotions = 0
        var callbacks = 0
        var clock = 0L
        var gone = false
        var rejectRollback = false
        val queue = ArrayDeque<() -> Unit>()
        val session = ExchangeSession(
            17, 200L,
            ExchangeSession.Origin(12, 0, "m", 0, "a"),
            ExchangeSession.Origin(13, 0, "s", 7, "b"),
            { clock }, { queue.addLast(it) },
            object : ExchangeSession.Driver {
                override fun promote() {
                    check(mainDisplay == 0 && slotDisplay == 7 && !gone)
                    promotions++
                    slotDisplay = 0
                    if (trial % 31 == 0) error("Binder changed state then threw")
                }
                override fun demoteAndVerify() {
                    check(mainDisplay == 0 && slotDisplay == 0 && !gone)
                    demotions++
                    mainDisplay = 7
                    if (trial % 41 == 0) error("Binder demotion threw after moving")
                }
                override fun restoreAndVerify() {
                    check(!gone && !rejectRollback)
                    if (promotions == 0) {
                        check(mainDisplay == 0 && slotDisplay == 7)
                    } else {
                        check(mainDisplay in setOf(0, 7) && slotDisplay in setOf(0, 7))
                        mainDisplay = 0
                        slotDisplay = 7
                    }
                }
            },
            { callbacks++; if (trial % 43 == 0) error("broken animation callback") },
        )
        session.start()
        if (random.nextInt(8) == 0) { mainDisplay = 9; session.cancel() }
        if (random.nextInt(8) == 0) { slotDisplay = 9; session.cancel() }
        if (random.nextInt(6) == 0) session.cancel()
        if (random.nextInt(9) == 0) clock = 201
        if (queue.isNotEmpty()) queue.removeFirst().invoke()
        if (!session.settled && session.phase != ExchangeSession.Phase.RECOVERY_REQUIRED) {
            if (random.nextInt(9) == 0) mainDisplay = 9
            if (random.nextInt(9) == 0) slotDisplay = 9
            if (random.nextInt(11) == 0) gone = true
            if (random.nextInt(11) == 0) rejectRollback = true
            if (random.nextInt(4) == 0) session.cancel()
            if (random.nextInt(7) == 0) clock = 201
        }
        repeat(8) { if (queue.isNotEmpty()) queue.removeFirst().invoke() }
        check(queue.isEmpty()) { "unbounded callbacks in trial $trial" }
        check(promotions <= 1 && demotions <= 1 && callbacks <= 1) { "duplicate operation in trial $trial" }
        when (session.phase) {
            ExchangeSession.Phase.COMMITTED -> check(mainDisplay == 7 && slotDisplay == 0 && callbacks == 1)
            ExchangeSession.Phase.RESTORED -> check(mainDisplay == 0 && slotDisplay == 7 && callbacks == 1)
            ExchangeSession.Phase.RECOVERY_REQUIRED -> check(!session.settled && callbacks == 0)
            else -> error("nonterminal but no scheduled recovery in trial $trial: ${session.phase}")
        }
    }
    println("STRESS PASS: $total deterministic transaction scenarios")
}
