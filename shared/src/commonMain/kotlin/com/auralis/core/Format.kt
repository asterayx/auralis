package com.auralis.core

import kotlin.math.abs
import kotlin.math.round

/** Common string formatting so JVM and Apple Native share one implementation. */
internal fun formatFixed1(value: Double): String {
    val scaled = round(value * 10.0).toLong()
    val sign = if (scaled < 0) "-" else ""
    val absScaled = abs(scaled)
    return "$sign${absScaled / 10}.${absScaled % 10}"
}

internal fun pad2(value: Long): String = value.toString().padStart(2, '0')

internal fun pad3(value: Long): String = value.toString().padStart(3, '0')
