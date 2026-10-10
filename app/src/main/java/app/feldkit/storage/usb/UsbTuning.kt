package app.feldkit.storage.usb

/** Knobs for the USB path. Defaults are the conservative, proven behaviour; the pipelined read path is on by default (measured 4-5x faster on a Crucial X8) and switches itself off on any error. */
object UsbTuning {
    /** Per-transfer logging of every USB packet (very noisy and slow; for debugging only). */
    @Volatile var verbose = false
    /** Queue many bulk-in requests at once instead of one synchronous call at a time. Off until measured on the user's drive. */
    @Volatile var fastReads = true
    /** Read a command's data phase as a series of bulkTransfer calls of at most this many bytes (0 = one call). */
    @Volatile var receiveChunk = 0
    /** Timeout for one bulk read, ms. */
    @Volatile var receiveTimeoutMs = 3000
    /** Sectors per READ/WRITE command; falls back to 256 (128 KB) automatically if the drive rejects a larger one. */
    @Volatile var maxCommandSectors = 1024
    const val SAFE_COMMAND_SECTORS = 256
}
