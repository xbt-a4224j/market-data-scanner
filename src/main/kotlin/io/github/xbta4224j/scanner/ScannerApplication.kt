package io.github.xbta4224j.scanner

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableCaching
@EnableAsync
class ScannerApplication

fun main(args: Array<String>) {
    runApplication<ScannerApplication>(*args)
}
