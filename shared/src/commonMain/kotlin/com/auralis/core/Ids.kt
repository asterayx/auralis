package com.auralis.core

import kotlin.random.Random

fun newId(prefix: String = ""): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val body = buildString(16) {
        repeat(16) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }
    return if (prefix.isEmpty()) body else "$prefix-$body"
}
