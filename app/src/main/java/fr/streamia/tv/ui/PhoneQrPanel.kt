package fr.streamia.tv.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import fr.streamia.tv.R
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fr.streamia.tv.data.PhoneEntryServer
import fr.streamia.tv.ui.theme.HeadingWeight
import fr.streamia.tv.ui.theme.Ink
import fr.streamia.tv.ui.theme.MutedInk

/** QR code permanent de la page login : le téléphone choisit Xtream ou M3U et envoie ses champs (thread principal). */
// Le logo est un fichier image (webp) lu tel quel pour la page du téléphone : openRawResource convient, malgré l'alerte lint.
@SuppressLint("ResourceType")
@Composable
fun PhoneQrPanel(onSubmit: (Map<String, String>) -> Unit) {
    val main = remember { Handler(Looper.getMainLooper()) }
    val latest = rememberUpdatedState(onSubmit)
    val resources = LocalContext.current.resources
    val server = remember {
        val logo = runCatching { resources.openRawResource(R.drawable.streamia_logo_mark).use { it.readBytes() } }.getOrNull()
        PhoneEntryServer(logo) { fields -> main.post { latest.value(fields) } }
    }
    val url = remember { runCatching { server.start() }.getOrNull() }
    DisposableEffect(Unit) { onDispose { server.close() } }
    val qr = remember(url) { url?.let(::qrBitmap) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (qr != null) {
            Image(qr.asImageBitmap(), "QR code", Modifier.size(132.dp), filterQuality = FilterQuality.None)
            Spacer(Modifier.width(16.dp))
        }
        Column {
            Text("Saisie depuis le téléphone", color = Ink, fontSize = 16.sp, fontWeight = HeadingWeight)
            Spacer(Modifier.height(4.dp))
            Text(
                if (qr != null) "Scannez, choisissez Xtream ou M3U, remplissez puis touchez « Connexion »."
                else "Aucun réseau local détecté : connectez la TV au Wi-Fi ou à l'Ethernet.",
                color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

internal fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val quiet = 2 // marge blanche, indispensable pour la détection
    val size = m.width + 2 * quiet
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        eraseColor(AColor.WHITE)
        for (x in 0 until m.width) for (y in 0 until m.height) if (m[x, y]) setPixel(x + quiet, y + quiet, AColor.BLACK)
    }
}
