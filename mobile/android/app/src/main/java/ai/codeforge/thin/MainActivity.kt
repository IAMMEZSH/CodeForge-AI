package ai.codeforge.thin

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var prefs: android.content.SharedPreferences

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        web = WebView(this)
        web.setBackgroundColor(0xFF05070C.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = "$userAgentString CodeForgeThin/1.0"
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.setOnLongClickListener {
            showServerDialog()
            true
        }

        setContentView(web)
        val saved = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        web.loadUrl(saved)

        if (!prefs.getBoolean(KEY_HINTED, false)) {
            prefs.edit().putBoolean(KEY_HINTED, true).apply()
            Toast.makeText(this, "Long-press the screen to change the server URL",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun showServerDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(KEY_URL, DEFAULT_URL))
            setSelection(text.length)
            setTextColor(0xFFE6F1FF.toInt())
            setHintTextColor(0xFF5B6B82.toInt())
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("CodeForge server")
            .setMessage("Desktop: http://<LAN-IP>:8765/console/\n" +
                "Termux on this device: http://127.0.0.1:8765/console/\n" +
                "Android emulator: http://10.0.2.2:8765/console/")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val url = normalize(input.text.toString())
                prefs.edit().putString(KEY_URL, url).apply()
                web.loadUrl(url)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reload") { _, _ -> web.reload() }
            .show()
    }

    private fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return DEFAULT_URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.contains("/console")) {
            url = url.trimEnd('/') + "/console/"
        }
        return url
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "codeforge"
        private const val KEY_URL = "server_url"
        private const val KEY_HINTED = "hint_shown"
        private const val DEFAULT_URL = "http://127.0.0.1:8765/console/"
    }
}
