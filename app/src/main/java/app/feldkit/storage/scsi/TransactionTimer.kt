package app.feldkit.storage.scsi

object TransactionTimer {

    inline fun <T> measure(

        block: () -> T

    ): Pair<T, Long> {

        val start = System.currentTimeMillis()

        val result = block()

        return Pair(

            result,

            System.currentTimeMillis() - start

        )

    }

}