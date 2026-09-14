package com.sakata.focusflow

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.util.UUID

/**
 * 只承载浙江大学官方登录与课表页。FocusFlow 不自动填写、不保存统一身份认证凭据，
 * 也不复制会话 Cookie；课表 JSON 仅在同源页面内通过 fetch 读取。
 */
class ZjuTimetableImportActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var importButton: Button
    private val bridgeToken = UUID.randomUUID().toString()
    private var bridgeAttached = false
    private var timetableRequested = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "浙江大学教务导入"

        status = TextView(this).apply {
            text = "请在浙大官方页面完成统一身份认证。登录成功后会打开“学生课表查询”。"
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        importButton = Button(this).apply {
            text = "读取当前课表"
            isEnabled = false
            setOnClickListener {
                isEnabled = false
                status.text = "正在读取当前选择的学年与学期…"
                webView.evaluateJavascript(captureScript(), null)
            }
        }
        val footer = TextView(this).apply {
            text = "账号密码只提交给浙江大学统一身份认证；FocusFlow 不读取或保存账号密码，导入课程仍需逐项确认。"
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            addView(webView)
            addView(importButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && isOfficialZjuHost(uri.host)) return false
                if (uri.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                detachBridge()
                importButton.isEnabled = false
                progress.visibility = android.view.View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = android.view.View.GONE
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
                if (isTimetablePage(uri)) {
                    attachBridge()
                    importButton.isEnabled = true
                    status.text = "请先在页面中确认学年、学期，再点“读取当前课表”。"
                    return
                }
                if (!timetableRequested && uri.scheme == "https" && uri.host.equals(ZDBK_HOST, ignoreCase = true) &&
                    uri.path?.startsWith("/jwglxt/") == true && uri.path?.contains("login_ssologin") != true
                ) {
                    timetableRequested = true
                    status.text = "登录成功，正在打开学生课表查询…"
                    view.loadUrl(TIMETABLE_INDEX_URL)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        webView.loadUrl(LOGIN_URL)
    }

    override fun onDestroy() {
        detachBridge()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun attachBridge() {
        if (bridgeAttached) return
        webView.addJavascriptInterface(TimetableBridge(), BRIDGE_NAME)
        bridgeAttached = true
    }

    private fun detachBridge() {
        if (!bridgeAttached || !::webView.isInitialized) return
        webView.removeJavascriptInterface(BRIDGE_NAME)
        bridgeAttached = false
    }

    private inner class TimetableBridge {
        @JavascriptInterface
        fun onTimetable(token: String, payload: String) {
            if (token != bridgeToken || payload.length > MAX_PAYLOAD_CHARS) return
            runOnUiThread {
                when (val parsed = ZjuTimetableParser.parse(payload)) {
                    is ZjuTimetableParseResult.Success -> {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_TIMETABLE_PAYLOAD, payload))
                        finish()
                    }
                    is ZjuTimetableParseResult.Failure -> {
                        importButton.isEnabled = true
                        status.text = parsed.message
                        Toast.makeText(this@ZjuTimetableImportActivity, parsed.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun onError(token: String, message: String) {
            if (token != bridgeToken) return
            runOnUiThread {
                importButton.isEnabled = true
                status.text = message.take(200)
                Toast.makeText(this@ZjuTimetableImportActivity, status.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun captureScript(): String = """
        (function() {
            const token = "${bridgeToken}";
            const bridge = window.FocusFlowImport;
            const year = document.querySelector("#xnm");
            const term = document.querySelector("#xqm");
            if (!bridge || !year || !term || !year.value || !term.value) {
                if (bridge) bridge.onError(token, "请先在课表页选择学年和学期。");
                return;
            }
            const termName = term.options && term.selectedIndex >= 0 ? term.options[term.selectedIndex].text : "";
            const body = new URLSearchParams({
                xnm: year.value, xqm: term.value, xqmmc: termName,
                xxqf: "0", xsfs: "0", captcha_value: ""
            });
            fetch("/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N253508", {
                method: "POST",
                credentials: "same-origin",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With": "XMLHttpRequest"
                },
                body: body.toString()
            }).then(async function(response) {
                const text = await response.text();
                if (!response.ok) throw new Error("教务系统返回 HTTP " + response.status);
                return text;
            }).then(function(text) {
                bridge.onTimetable(token, text);
            }).catch(function(error) {
                bridge.onError(token, "读取课表失败：" + String(error && error.message ? error.message : error));
            });
        })();
    """.trimIndent()

    private fun isTimetablePage(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host.equals(ZDBK_HOST, ignoreCase = true) &&
            uri.path == "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html"

    private fun isOfficialZjuHost(host: String?): Boolean {
        val value = host?.lowercase().orEmpty()
        return value == "zju.edu.cn" || value.endsWith(".zju.edu.cn")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TIMETABLE_PAYLOAD = "zju_timetable_payload"
        private const val ZDBK_HOST = "zdbk.zju.edu.cn"
        private const val BRIDGE_NAME = "FocusFlowImport"
        private const val MAX_PAYLOAD_CHARS = 750_000
        private const val LOGIN_URL =
            "https://zjuam.zju.edu.cn/cas/login?service=https%3A%2F%2Fzdbk.zju.edu.cn%2Fjwglxt%2Fxtgl%2Flogin_ssologin.html"
        private const val TIMETABLE_INDEX_URL =
            "https://zdbk.zju.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508&layout=default"
    }
}
