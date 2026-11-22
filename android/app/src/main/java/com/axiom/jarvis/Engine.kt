package com.axiom.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Downloads brains from Hugging Face straight onto the phone. */
class ModelManager(ctx: Context) {
    val dir = File(ctx.filesDir, "models").apply { mkdirs() }

    data class Progress(@Volatile var status: String = "none", @Volatile var done: Long = 0,
                        @Volatile var total: Long = 0, @Volatile var error: String? = null)

    private val progress = ConcurrentHashMap<String, Progress>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun model(id: String) = File(dir, "$id.gguf")
    fun mmproj(id: String) = File(dir, "$id.mmproj.gguf")
    fun installed(id: String): Boolean {
        val b = Brains.get(id) ?: return false
        return model(id).exists() && (!b.vision || mmproj(id).exists())
    }
    fun installedSet() = Brains.all.map { it.id }.filter(::installed).toSet()

    fun json(): JSONArray = JSONArray().apply {
        Brains.all.forEach { b ->
            val p = progress[b.id]
            put(JSONObject().put("id", b.id).put("title", b.title).put("blurb", b.blurb)
                .put("repo", b.repo).put("gb", b.approxGb).put("vision", b.vision)
                .put("status", if (installed(b.id)) "ready" else p?.status ?: "none")
                .put("done", p?.done ?: 0).put("total", p?.total ?: 0).put("error", p?.error ?: JSONObject.NULL))
        }
    }

    fun delete(id: String) {
        cancelled += id
        dir.listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() }
        progress.remove(id)
    }

    fun cancel(id: String) { cancelled += id }

    fun download(id: String) {
        val b = Brains.get(id) ?: return
        if (installed(id) || progress[id]?.status == "downloading") return
        val p = Progress("downloading").also { progress[id] = it }
        cancelled -= id
        Thread({
            try {
                val tree = JSONArray(get("https://huggingface.co/api/models/${b.repo}/tree/main"))
                val sizes = (0 until tree.length()).map { tree.getJSONObject(it) }
                    .associate { it.getString("path") to it.optLong("size") }
                val files = mutableListOf<Pair<String, File>>()
                val m = HfPick.model(sizes.keys.toList(), b.quant) ?: error("no ${b.quant} file in ${b.repo}")
                files += m to model(id)
                if (b.vision) files += (HfPick.mmproj(sizes.keys.toList()) ?: error("no mmproj in ${b.repo}")) to mmproj(id)
                p.total = files.sumOf { sizes[it.first] ?: 0L }
                var base = 0L
                for ((path, dest) in files) {
                    if (!dest.exists()) fetch("https://huggingface.co/${b.repo}/resolve/main/$path", dest, id) { p.done = base + it }
                    base += sizes[path] ?: 0L
                }
                p.status = "ready"
            } catch (e: Exception) {
                p.status = if (id in cancelled) "none" else "error"
                p.error = if (id in cancelled) null else (e.message ?: e.javaClass.simpleName)
            }
        }, "dl-$id").start()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        if (c.responseCode != 200) error("HTTP ${c.responseCode} for $url")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Resumable download into dest.part, renamed when complete. */
    private fun fetch(url: String, dest: File, id: String, onBytes: (Long) -> Unit) {
        val part = File(dest.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000; c.instanceFollowRedirects = true
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code != 200 && code != 206) error("HTTP $code downloading ${dest.name}")
        val append = code == 206
        var n = if (append) have else 0L
        c.inputStream.use { inp ->
            java.io.FileOutputStream(part, append).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    if (id in cancelled) error("cancelled")
                    val r = inp.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r); n += r; onBytes(n)
                }
            }
        }
        if (!part.renameTo(dest)) error("could not save ${dest.name}")
    }
}

/** Runs llama-server (bundled as libllama_server.so) with one brain loaded at a time. */
class Engine(private val ctx: Context, private val models: ModelManager) {
    val port = 8080
    @Volatile var current: String? = null
        private set
    @Volatile var loading: String? = null
        private set
    private var proc: Process? = null
    private val logDir = File(ctx.filesDir, "logs").apply { mkdirs() }

    fun healthy(): Boolean = try {
        val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 1500
        c.responseCode == 200
    } catch (e: Exception) { false }

    @Synchronized fun ensure(id: String) {
        if (current == id && proc?.isAlive == true && healthy()) return
        stop()
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libllama_server.so")
        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(8).let { if (it >= 8) 4 else it.coerceAtLeast(2) }
        val args = mutableListOf(bin.path, "-m", models.model(id).path, "--host", "127.0.0.1", "--port", "$port",
            "-t", "$threads", "-c", "4096", "--jinja", "--no-webui")
        if (Brains.get(id)?.vision == true) args += listOf("--mmproj", models.mmproj(id).path)
        val log = File(logDir, "$id.log")
        loading = id
        try {
            proc = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
            val deadline = System.currentTimeMillis() + 240_000
            while (System.currentTimeMillis() < deadline) {
                if (healthy()) { current = id; return }
                if (proc?.isAlive != true) error("engine stopped: " + log.readLines().takeLast(3).joinToString(" | "))
                Thread.sleep(500)
            }
            error("engine did not start in time")
        } finally { loading = null }
    }

    @Synchronized fun stop() {
        proc?.let { p ->
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        proc = null; current = null
    }
}
