package com.fatalpuppet.volumex.storage.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.util.Log

import com.fatalpuppet.volumex.storage.usb.UsbUasInterface
private const val USB_MASS_STORAGE_SUBCLASS = 0x06
private const val USB_UAS_PROTOCOL = 0x62
class UsbInterfaceScanner {

    fun findMassStorageInterface(
        device: UsbDevice
    ): UsbMassStorageInterface? {

        for (i in 0 until device.interfaceCount) {

            val usbInterface = device.getInterface(i)

            // We want:
            // Class    = Mass Storage (0x08)
            // Subclass = SCSI Transparent (0x06)
            // Protocol = Bulk-Only Transport (0x50)
            if (
                usbInterface.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE ||
                usbInterface.interfaceSubclass != 0x06 ||
                usbInterface.interfaceProtocol != 0x50
            ) {
                continue
            }

            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null

            for (e in 0 until usbInterface.endpointCount) {

                val endpoint = usbInterface.getEndpoint(e)

                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK)
                    continue

                when (endpoint.direction) {

                    UsbConstants.USB_DIR_IN ->
                        bulkIn = endpoint

                    UsbConstants.USB_DIR_OUT ->
                        bulkOut = endpoint
                }
            }

            if (bulkIn != null && bulkOut != null) {

                Log.d(
                    "VolumeX",
                    "Selected USB mass-storage interface $i " +
                            "(SCSI Transparent / Bulk-Only)"
                )

                Log.d(
                    "VolumeX",
                    "Selected endpoints: interface=$i " +
                            "IN=0x${"%02X".format(bulkIn.address)} " +
                            "IN maxPacket=${bulkIn.maxPacketSize} " +
                            "OUT=0x${"%02X".format(bulkOut.address)} " +
                            "OUT maxPacket=${bulkOut.maxPacketSize}"
                )

                Log.d(
                    "VolumeX",
                    "Selected endpoints: interface=$i " +
                            "IN=0x${"%02X".format(bulkIn.address)} " +
                            "IN maxPacket=${bulkIn.maxPacketSize} " +
                            "OUT=0x${"%02X".format(bulkOut.address)} " +
                            "OUT maxPacket=${bulkOut.maxPacketSize}"
                )

                return UsbMassStorageInterface(
                    usbInterface = usbInterface,
                    bulkIn = bulkIn,
                    bulkOut = bulkOut,
                    interfaceNumber = i
                )
            }
        }

        Log.e(
            "VolumeX",
            "No SCSI/Bulk-Only mass-storage interface found"
        )

        return null
    }

    fun findUasInterface(
        device: UsbDevice
    ): UsbUasInterface? {

        for (i in 0 until device.interfaceCount) {

            val usbInterface = device.getInterface(i)

            if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE)
                continue

            if (usbInterface.interfaceSubclass != 0x06)
                continue

            // UAS protocol = 0x62 = 98 decimal
            if (usbInterface.interfaceProtocol != 0x62)
                continue

            val bulkInEndpoints = mutableListOf<UsbEndpoint>()
            val bulkOutEndpoints = mutableListOf<UsbEndpoint>()

            for (e in 0 until usbInterface.endpointCount) {

                val endpoint = usbInterface.getEndpoint(e)

                Log.d(
                    "VolumeX",
                    "UAS endpoint descriptor: " +
                            "address=0x${"%02X".format(endpoint.address)} " +
                            "direction=${endpoint.direction} " +
                            "type=${endpoint.type} " +
                            "maxPacket=${endpoint.maxPacketSize} " +
                            "interval=${endpoint.interval}"
                )

                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK)
                    continue

                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    bulkInEndpoints.add(endpoint)
                } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    bulkOutEndpoints.add(endpoint)
                }
            }

            if (bulkInEndpoints.size != 2 || bulkOutEndpoints.size != 2) {
                Log.w(
                    "VolumeX",
                    "UAS interface $i does not have 2 IN + 2 OUT bulk endpoints"
                )
                continue
            }

            val commandOut =
                bulkOutEndpoints.first { it.address == 0x02 }

            val statusIn =
                bulkInEndpoints.first { it.address == 0x81 }

            val dataIn =
                bulkInEndpoints.first { it.address == 0x83 }

            val dataOut =
                bulkOutEndpoints.first { it.address == 0x04 }

            Log.i(
                "VolumeX",
                "UAS interface detected: $i"
            )

            Log.i(
                "VolumeX",
                "UAS endpoints: " +
                        "CMD OUT=0x${"%02X".format(commandOut.address)}, " +
                        "STATUS IN=0x${"%02X".format(statusIn.address)}, " +
                        "DATA IN=0x${"%02X".format(dataIn.address)}, " +
                        "DATA OUT=0x${"%02X".format(dataOut.address)}"
            )

            return UsbUasInterface(
                usbInterface = usbInterface,
                commandOut = commandOut,
                statusIn = statusIn,
                dataIn = dataIn,
                dataOut = dataOut,
                interfaceNumber = i
            )
        }

        return null
    }

    fun inspectDevice(
        device: UsbDevice
    ): List<UsbInterfaceInfo> {
        val interfaces = mutableListOf<UsbInterfaceInfo>()
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            var bulkIn = false
            var bulkOut = false
            for (e in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(e)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK)
                    continue
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN ->
                        bulkIn = true
                    UsbConstants.USB_DIR_OUT ->
                        bulkOut = true
                }
            }
            Log.d(
                "VolumeX",
                "Interface $i | " +
                        "Class=${usbInterface.interfaceClass} | " +
                        "Subclass=${usbInterface.interfaceSubclass} | " +
                        "Protocol=${usbInterface.interfaceProtocol} | " +
                        "Endpoints=${usbInterface.endpointCount} | " +
                        "Bulk IN=$bulkIn | " +
                        "Bulk OUT=$bulkOut"
            )
            interfaces.add(
                UsbInterfaceInfo(
                    interfaceNumber = i,
                    interfaceClass = usbInterface.interfaceClass,
                    endpointCount = usbInterface.endpointCount,
                    bulkInFound = bulkIn,
                    bulkOutFound = bulkOut
                )
            )
        }
        return interfaces
    }

}