package com.plm.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.plm"])
class PlmApplication

fun main(args: Array<String>) {
    runApplication<PlmApplication>(*args)
}
