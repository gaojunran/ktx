#!/usr/bin/env ktx

@file:DependsOn("io.ktor:ktor-client-core-jvm:3.2.0")
@file:DependsOn("io.ktor:ktor-client-cio-jvm:3.2.0")

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking

runBlocking {
    HttpClient(CIO).use { client ->
        val body = client.get("https://httpbin.org/uuid").bodyAsText()
        println("server returned: $body")
    }
}
