package com.axiom.jarvis

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MemoryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "memory.md")
    @Synchronized fun read(): String = if (file.exists()) file.readText() else ""
    @Synchronized fun write(text: String) = file.writeText(text)
    @Synchronized fun remember(fact: String) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        file.appendText("- ${fact.trim()}  ($day)\n")
    }
}

/** Local API + HUD page on 127.0.0.1:7777. Mirrors the Termux `axiom hud` endpoints. */
class HudServer(private val ctx: Context, private val models: ModelManager,
                private val engine: Engine, private val memory: MemoryStore) {
    val port = 7777
    private var socket: ServerSocket? = null
    private val icons = ConcurrentHashMap<String, ByteArray>()

    fun start() {
        if (socket != null) return
        val s = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).also { socket = it }
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                Thread({ handle(c) }, "hud-conn").start()
            }
        }, "hud-server").start()
    }

    fun stop() { socket?.close(); socket = null }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val body: String)

    private fun readReq(inp: InputStream): Req? {
        val bin = BufferedInputStream(inp)
        fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = bin.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }
        val first = line() ?: return null
        val parts = first.split(" ")
        if (parts.size < 2) return null
        var len = 0
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0 && h.substring(0, i).equals("content-length", true)) len = h.substring(i + 1).trim().toInt()
        }
        val body = ByteArray(len)
        var got = 0
        while (got < len) { val r = bin.read(body, got, len - got); if (r < 0) break; got += r }
        val (path, qs) = parts[1].split("?", limit = 2).let { it[0] to it.getOrNull(1) }
        val query = qs?.split("&")?.mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8") else null
        }?.toMap() ?: emptyMap()
        return Req(parts[0], path, query, String(body, Charsets.UTF_8))
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        out.write(("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }

    private fun json(out: OutputStream, obj: Any, code: Int = 200) =
        send(out, code, "application/json", obj.toString().toByteArray())

    private fun handle(c: Socket) = c.use {
        try {
            val req = readReq(c.getInputStream()) ?: return
            val out = c.getOutputStream()
            val body = if (req.body.isNotBlank() && req.body.trim().startsWith("{")) JSONObject(req.body) else JSONObject()
            when ("${req.method} ${req.path}") {
                "GET /", "GET /index.html" -> send(out, 200, "text/html; charset=utf-8", ctx.assets.open("hud.html").readBytes())
                "GET /api/status" -> json(out, vitals())
                "GET /api/models" -> json(out, models.json())
                "POST /api/download" -> { models.download(body.optString("brain")); json(out, models.json()) }
                "POST /api/cancel" -> { models.cancel(body.optString("brain")); json(out, models.json()) }
                "POST /api/delete" -> {
                    val id = body.optString("brain")
                    if (engine.current == id) engine.stop()
                    models.delete(id); json(out, models.json())
                }
                "POST /api/stop" -> { engine.stop(); json(out, JSONObject().put("ok", true)) }
                "GET /api/memory" -> json(out, JSONObject().put("memory", memory.read()))
                "POST /api/memory" -> { memory.write(body.optString("memory")); json(out, JSONObject().put("ok", true)) }
                "GET /api/apps" -> json(out, apps())
                "GET /api/icon" -> send(out, 200, "image/png", icon(req.query["pkg"] ?: ""))
                "POST /api/chat" -> chat(out, body)
                else -> json(out, JSONObject().put("error", "not found"), 404)
            }
        } catch (e: Exception) { /* client went away */ }
    }

    private fun vitals(): JSONObject {
        val v = JSONObject().put("brain", engine.current ?: JSONObject.NULL)
            .put("loading", engine.loading ?: JSONObject.NULL)
            .put("up", engine.current != null)
            .put("installed", JSONArray(models.installedSet().toList()))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        v.put("ram", ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt())
        v.put("ramGb", Math.round(mi.totalMem / 1e9))
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
            val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0) v.put("battery", lvl * 100 / scale)
            v.put("temp", b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            v.put("charging", b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
        return v
    }

    private fun apps(): JSONArray {
        val pm = ctx.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first.lowercase() }
        return JSONArray().apply { list.forEach { put(JSONObject().put("label", it.first).put("pkg", it.second)) } }
    }

    private fun icon(pkg: String): ByteArray = icons.getOrPut(pkg) {
        val d = try { ctx.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { ctx.packageManager.defaultActivityIcon }
        val bmp = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 144, 144); d.draw(Canvas(bmp))
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Streams NDJSON: {"brain"}, {"d": text}..., {"error"}?, {"done"}. */
    private fun chat(out: OutputStream, req: JSONObject) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        fun emit(o: JSONObject) { out.write((o.toString() + "\n").toByteArray()); out.flush() }
        val msg = req.optString("message")
        val image = req.optString("image").takeIf { it.startsWith("data:") }
        try {
            if (image == null) Memory.REMEMBER.find(msg)?.let {
                memory.remember(it.groupValues[1])
                emit(JSONObject().put("brain", "memory"))
                emit(JSONObject().put("d", "Stored in memory core: \"${it.groupValues[1].trim()}\""))
                emit(JSONObject().put("done", true)); return
            }
            val installed = models.installedSet()
            val want = req.optString("brain", "auto")
            val brain = when {
                image != null -> "vision"
                want == "auto" || want.isEmpty() -> Router.route(msg, installed)
                else -> want
            }
            if (brain == null || brain !in installed) {
                val name = brain?.uppercase() ?: "any"
                emit(JSONObject().put("error", "No $name core installed yet. Tap CORES to download it."))
                emit(JSONObject().put("done", true)); return
            }
            emit(JSONObject().put("brain", brain).put("loading", engine.current != brain))
            engine.ensure(brain)
            emit(JSONObject().put("ready", true))

            val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
            val system = "You are AXIOM, a Jarvis-style AI running fully offline on the user's ${Build.MANUFACTURER} ${Build.MODEL}. " +
                    "It is $now. Be concise, precise and a little witty. Use markdown code blocks for code.\n\n" +
                    "What you know about the user:\n" + memory.read().ifBlank { "(nothing yet)" }
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            req.optJSONArray("history")?.let { h ->
                for (i in maxOf(0, h.length() - 8) until h.length()) messages.put(h.getJSONObject(i))
            }
            val content: Any = if (image != null) JSONArray()
                .put(JSONObject().put("type", "text").put("text", msg.ifBlank { "Describe what you see." }))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image)))
            else msg
            messages.put(JSONObject().put("role", "user").put("content", content))

            val c = URL("http://127.0.0.1:${engine.port}/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.readTimeout = 300_000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("messages", messages).put("stream", true).toString().toByteArray()) }
            if (c.responseCode != 200) error("engine HTTP ${c.responseCode}: " + (c.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""))
            val tagger = ThinkTagger()
            c.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { l -> tagger.feed(l)?.let { emit(JSONObject().put("d", it)) } }
            }
            tagger.finish()?.let { emit(JSONObject().put("d", it)) }
        } catch (e: Exception) {
            try { emit(JSONObject().put("error", e.message ?: e.javaClass.simpleName)) } catch (_: Exception) {}
        }
        try { emit(JSONObject().put("done", true)) } catch (_: Exception) {}
    }
}
