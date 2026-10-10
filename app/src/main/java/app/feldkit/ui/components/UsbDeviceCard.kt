package app.feldkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.feldkit.data.models.UsbDeviceInfo

@Composable
fun UsbDeviceCard(
    device: UsbDeviceInfo
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = device.productName ?: "Unknown Device",
                style = MaterialTheme.typography.titleMedium
            )
            Text("Manufacturer : ${device.manufacturer ?: "Unknown"}")
            Text("Serial Number : ${device.serialNumber ?: "Unavailable"}")
            Text("Vendor ID : 0x${device.vendorId.toString(16).uppercase()}")
            Text("Product ID : 0x${device.productId.toString(16).uppercase()}")
            Text("Permission : ${
                    if (device.permissionGranted)
                        "Granted"
                    else
                        "Required"
                }"
            )
        }
    }
}