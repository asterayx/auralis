package com.auralis.provider.stt

import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.Channel

internal suspend fun DefaultWebSocketSession.receiveFrames(onFrame: suspend (Frame) -> Unit) {
    while (true) {
        val result = incoming.receiveCatching()
        val frame = result.getOrNull() ?: break
        onFrame(frame)
    }
}

internal suspend fun drainOutgoing(
    send: suspend (Frame) -> Unit,
    frames: Channel<Frame>,
) {
    for (frame in frames) {
        send(frame)
    }
}
