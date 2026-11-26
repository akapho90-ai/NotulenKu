package com.example.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HttpReply(val code: Int, val body: String?) {
    val isSuccessful: Boolean get() = code in 200..299
}

internal suspend fun Call.readReply(): HttpReply = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use { HttpReply(it.code, it.body?.string()) } }
            result.fold(continuation::resume, continuation::resumeWithException)
        }
    })
}
