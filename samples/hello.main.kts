#!/usr/bin/env ktx

println("hello from main.kts")
println("args (${args.size}):")
args.forEachIndexed { i, a -> println("  [$i] $a") }
