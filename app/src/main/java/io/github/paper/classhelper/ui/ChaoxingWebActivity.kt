package io.github.paper.classhelper.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import io.github.paper.classhelper.ClassHelperApp

/** Authenticated, read-only viewer for Chaoxing resources that are not exposed as PDF. */
class ChaoxingWebActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "学习通资料" }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            finish()
            return
        }
        setContentView(buildContent(title))
        val app = application as ClassHelperApp
        installCookies(app.graph.settings.chaoxingCookie)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.userAgentString = webView.settings.userAgentString + " ClassHelper/1.0"
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        webView.loadUrl(url)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = null
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun installCookies(raw: String) {
        if (raw.isBlank()) return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val hosts = listOf(
            "https://chaoxing.com/",
            "https://i.chaoxing.com/",
            "https://passport2.chaoxing.com/",
            "https://mooc1.chaoxing.com/",
            "https://mooc1-2.chaoxing.com/",
            "https://mooc2-ans.chaoxing.com/",
        )
        val cookies = raw.split(';').map { it.trim() }.filter { '=' in it }
        hosts.forEach { host -> cookies.forEach { manager.setCookie(host, it) } }
        manager.flush()
    }

    private fun buildContent(title: String): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(8))
        }
        bar.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "‹"
            textSize = 24f
            isAllCaps = false
            minHeight = 0
            insetTop = 0
            insetBottom = 0
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(54), dp(48)))
        bar.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun intentFor(context: Context, url: String, title: String): Intent =
            Intent(context, ChaoxingWebActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
    }
}
