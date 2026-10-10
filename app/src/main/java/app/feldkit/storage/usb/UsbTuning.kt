package app.feldkit.storage.usb

/** Knobs for the USB path. Defaults are the conservative, proven behaviour; the fast read path is opt-in (Settings). */
object UsbTuning {
    /** Per-transfer logging of every USB packet (very noisy and slow; for debugging only). */
    @Volatile var verbose = false
    /** Queue many bulk-in requests at once instead of one synchronous call at a time. Off until measured on the user's drive. */
    @Volatile var fastReads = false
    /** Sectors per READ/WRITE command; falls back to 256 (128 KB) automatically if the drive rejects a larger one. */
    @Volatile var maxCommandSectors = 1024
    const val SAFE_COMMAND_SECTORS = 256
}
