package app.feldkit.storage.scsi

object ScsiTransactionHistory {

    private val history = mutableListOf<ScsiTransaction>()

    fun add(

        transaction: ScsiTransaction

    ) {

        history.add(transaction)

    }

    fun all(): List<ScsiTransaction> = history.toList()

    fun clear() {

        history.clear()

    }

}