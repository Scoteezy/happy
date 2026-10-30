package com.slopus.happy.harness

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.slopus.happy.direct.DirectHttpResponse
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

/** Only shell-fixture-picture-* computers serve these fictional identity pictures. */
object PictureShellFixture {
    const val PREFIX = "shell-fixture-picture-"
    const val HASH = "HwgKPxxwd3dweHiHiHiHd4h3Bwd4gI8I"

    fun response(address: String, path: String, original: DirectHttpResponse): DirectHttpResponse {
        if (!address.startsWith(PREFIX)) return original
        val kind = address.removePrefix(PREFIX)
        if (path == "/v0/config" && original.status == 200) {
            val root = Json.parseToJsonElement(original.body.decodeToString()).jsonObject
            val config = root.getValue("config").jsonObject
            val node = config.getValue("node").jsonObject
            val avatar = if (kind == "none") JsonNull else buildJsonObject { put("thumbhash", HASH) }
            return DirectHttpResponse(original.status, original.headers, JsonObject(root + ("config" to JsonObject(config +
                ("node" to JsonObject(node + ("avatar" to avatar)))))).toString().toByteArray())
        }
        if (path == "/v0/node/avatar" && kind == "photo") {
            val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.rgb(22, 165, 181))
                drawCircle(300f, 150f, 90f, Paint(3).apply { color = Color.rgb(255, 199, 51) })
            }
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
            }
            bitmap.recycle()
            return DirectHttpResponse(200, mapOf("content-type" to "image/png", "etag" to "fixture-picture-1"), bytes)
        }
        return original
    }
}