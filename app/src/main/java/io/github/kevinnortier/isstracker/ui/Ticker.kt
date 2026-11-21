package io.github.kevinnortier.isstracker.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Instant

/** Emits the current time once per second, aligned to whole seconds. */
fun secondTicker(clock: Clock = Clock.systemUTC()): Flow<Instant> = flow {
    while (true) {
        val now = clock.instant()
        emit(now)
        delay(1000L - now.toEpochMilli() % 1000L)
    }
}
