package ru.campus.equipment

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val port = System.getenv("HTTP_PORT") ?: "8080"
    val healthy = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/health")).timeout(Duration.ofSeconds(3)).GET().build()
        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)
    exitProcess(if (healthy) 0 else 1)
}
