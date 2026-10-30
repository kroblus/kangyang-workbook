package cn.kangyang.workbook.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cn.kangyang.workbook.data.Attachment
import cn.kangyang.workbook.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface PreviewImage {
    data object Loading : PreviewImage
    data class Ready(val bitmap: ImageBitmap) : PreviewImage
    data class Unavailable(val message: String) : PreviewImage
}

/** The dialog opens immediately; file integrity checks and sampled decoding stay off the UI thread. */
@Composable
fun AttachmentPreview(attachment: Attachment, repository: Repository, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val previewHeight = minOf(320, (configuration.screenHeightDp - 180).coerceAtLeast(120)).dp
    val requestedEdge = with(density) {
        maxOf((configuration.screenWidthDp - 80).coerceAtLeast(160).dp.roundToPx(), previewHeight.roundToPx())
    }
    val maxEdge = (((requestedEdge + 63) / 64) * 64).coerceIn(256, 1280)
    val image by produceState<PreviewImage>(PreviewImage.Loading, attachment.id, attachment.sha256, maxEdge) {
        value = PreviewImage.Loading
        value = try {
            withContext(Dispatchers.IO) {
                // Check once per open, then reuse the checked path for both decoder passes.
                val path = repository.attachmentFile(attachment).absolutePath
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
                val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bitmap == null) PreviewImage.Unavailable("图片无法读取，原记录和其他附件仍然保留")
                else {
                    bitmap.prepareToDraw()
                    PreviewImage.Ready(bitmap.asImageBitmap())
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            PreviewImage.Unavailable(failure.message ?: "图片无法读取，原记录和其他附件仍然保留")
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(attachment.name) }, text = {
        Box(Modifier.fillMaxWidth().height(previewHeight), contentAlignment = Alignment.Center) {
            when (val current = image) {
                PreviewImage.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("正在打开图片…", color = Muted)
                }
                is PreviewImage.Ready -> Image(current.bitmap, attachment.name, Modifier.fillMaxSize())
                is PreviewImage.Unavailable -> Text(current.message)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭预览") } })
}
