#!/usr/bin/env ktx

@file:Toolchain(jdk = "17")

println("Running on JDK ${Runtime.version().feature()}")
println("java.version = ${System.getProperty("java.version")}")
println("java.home = ${System.getProperty("java.home")}")
