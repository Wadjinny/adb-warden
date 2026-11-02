package com.wadjinny.adbwarden.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Returns a function that asks for a file name and writes the JSON produced by [build] there. */
@Composable
fun rememberJsonExporter(build: () -> JSONObject): (String) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { writeJson(ctx, uri, build()) }
            Toast.makeText(ctx, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
    return { name -> launcher.launch(name) }
}

private fun writeJson(ctx: Context, uri: Uri, o: JSONObject): Boolean = runCatching {
    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(o.toString(2).toByteArray()) }
}.isSuccess

fun stamp(): String = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
