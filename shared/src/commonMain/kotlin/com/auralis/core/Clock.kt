package com.auralis.core

fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System: Clock = Clock { systemClockNowMs() }
    }
}

internal expect fun systemClockNowMs(): Long
