package com.fatalpuppet.volumex.storage.scsi

import java.util.concurrent.atomic.AtomicInteger
object CommandTagGenerator {
    private val counter = AtomicInteger(1)

    fun next(): Int {
        return counter.getAndIncrement()
    }
}