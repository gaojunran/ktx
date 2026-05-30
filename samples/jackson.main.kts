#!/usr/bin/env ktx

@file:DependsOn("com.fasterxml.jackson.core:jackson-databind:2.18.0")

import com.fasterxml.jackson.databind.ObjectMapper

val data = mapOf("name" to "ktx", "phase" to "1.2", "lockfile" to true)
val json = ObjectMapper().writeValueAsString(data)
println(json)
