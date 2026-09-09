package io.github.paper.classhelper.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Policy-compliant bridge for non-PDF Chaoxing resources.
 * The app does not embed a browser runtime; the URL is delegated to the user's browser/app.
 */
class ChaoxingWebActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        finish()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun intentFor(context: Context, url: String, title: String): Intent =
            Intent(context, ChaoxingWebActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
    }
}
