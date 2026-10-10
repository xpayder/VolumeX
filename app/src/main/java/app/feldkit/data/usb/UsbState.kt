package app.feldkit.data.usb

enum class UsbState {
    WAITING,
    CONNECTED,
    PERMISSION_GRANTED,
    SCANNING,
    MOUNTED,
    ERROR
}