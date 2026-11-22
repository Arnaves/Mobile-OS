package com.axiom.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.util.Locale

class MainActivity : Activity() {
    companion object { const val EXTRA_VOICE = "axiom.voice" }

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var photo: File? = null
    private var pendingVoice = false
    private var loaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        startForegroundService(Intent(this, AxiomService::class.java))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        web = WebView(this).apply {
            setBackgroundColor(0xFF04070C.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(), "Native")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame) v.postDelayed({ v.reload() }, 600)
                }
                override fun onPageFinished(v: WebView, url: String) {
                    loaded = true
                    if (pendingVoice) { pendingVoice = false; js("window.axiomVoice && axiomVoice()") }
                }
            }
        }
        setContentView(web)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        pendingVoice = intent.wantsVoice()
        waitForCoreThenLoad()
    }

    private fun Intent?.wantsVoice() = this != null &&
        (getBooleanExtra(EXTRA_VOICE, false) || action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND)

    private fun waitForCoreThenLoad() = Thread {
        repeat(100) {
            try { Socket("127.0.0.1", 7777).close(); runOnUiThread { web.loadUrl("http://127.0.0.1:7777/") }; return@Thread }
            catch (e: Exception) { Thread.sleep(100) }
        }
        runOnUiThread { web.loadUrl("http://127.0.0.1:7777/") }
    }.start()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.wantsVoice()) { if (loaded) js("window.axiomVoice && axiomVoice()") else pendingVoice = true }
        else if (intent.hasCategory(Intent.CATEGORY_HOME)) js("window.axiomHome && axiomHome()")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.axiomBack ? axiomBack() : false") { r ->
            if (r != "true" && !isDefaultHome()) {
                @Suppress("DEPRECATION") super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown(); recognizer?.destroy()
        super.onDestroy()
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun isDefaultHome(): Boolean {
        val r = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == packageName
    }

    private fun isDefaultAssistant(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        return Settings.Secure.getString(contentResolver, "assistant")?.contains(packageName) == true
    }

    // ---------- voice ----------
    private fun recognizerComponent(): ComponentName? {
        // Avoid our own stub service (which becomes the default once AXIOM is the assistant).
        val services = packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != packageName }
        return services.firstOrNull { it.packageName.startsWith("com.google") } ?: services.firstOrNull()
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        recognizer?.destroy()
        val comp = recognizerComponent()
        recognizer = when {
            comp != null -> SpeechRecognizer.createSpeechRecognizer(this, comp)
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else -> { js("onVoiceEnd('No speech recognizer on this phone. Install Google app or FUTO Voice.')"); return }
        }
        recognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle) = emit(b, false)
            override fun onResults(b: Bundle) = emit(b, true)
            override fun onError(error: Int) = js("onVoiceEnd(${JSONObject.quote(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "" else "voice error $error")})")
            override fun onReadyForSpeech(p: Bundle?) = js("onVoiceReady()")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) = js("window.onVoiceLevel && onVoiceLevel($rms)")
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
            fun emit(b: Bundle, final: Boolean) {
                val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                js("onVoice(${JSONObject.quote(text)}, $final)")
            }
        })
        recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == 7 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
        else if (code == 7) js("onVoiceEnd('Microphone permission denied')")
    }

    // ---------- camera ----------
    private fun camera() {
        val dir = File(cacheDir, "eye").apply { mkdirs() }
        val f = File(dir, "eye.jpg").also { photo = it }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try { startActivityForResult(i, 99) } catch (e: Exception) { js("onPhoto(null)") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 99) return
        val f = photo
        if (res != RESULT_OK || f == null || !f.exists()) { js("onPhoto(null)"); return }
        Thread {
            val url = try { encodePhoto(f) } catch (e: Exception) { null }
            js("onPhoto(${if (url == null) "null" else JSONObject.quote(url)})")
        }.start()
    }

    private fun encodePhoto(f: File): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 896) sample *= 2
        var bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        val k = 896f / maxOf(bmp.width, bmp.height)
        if (k < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        val rot = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
        }
        if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Methods callable from the HUD as window.Native.* */
    inner class Bridge {
        private val actions = Actions(this@MainActivity)

        @JavascriptInterface fun command(text: String): String {
            val reply = actions.run(text) ?: return """{"handled":false}"""
            return JSONObject().put("handled", true).put("reply", reply).toString()
        }
        @JavascriptInterface fun launch(pkg: String) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
        }
        @JavascriptInterface fun appInfo(pkg: String) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
        @JavascriptInterface fun listen() = runOnUiThread { this@MainActivity.listen() }
        @JavascriptInterface fun stopListening() = runOnUiThread { recognizer?.stopListening() }
        @JavascriptInterface fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "axiom") }
        @JavascriptInterface fun hush() { tts?.stop() }
        @JavascriptInterface fun camera() = runOnUiThread { this@MainActivity.camera() }
        @JavascriptInterface fun system(): String = JSONObject()
            .put("home", isDefaultHome()).put("assistant", isDefaultAssistant())
            .put("mic", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .toString()
        @JavascriptInterface fun setHome() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 5); return@runOnUiThread
                }
            }
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        @JavascriptInterface fun setAssistant() = runOnUiThread {
            val tries = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
            for (i in tries) try { startActivity(i); return@runOnUiThread } catch (e: Exception) {}
        }
        @JavascriptInterface fun askMic() = runOnUiThread { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8) }
    }
}
