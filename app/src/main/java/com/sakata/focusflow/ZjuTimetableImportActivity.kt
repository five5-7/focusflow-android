package com.sakata.focusflow

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * “从教务网导入 → 浙江大学”账号输入页。
 * 密码不保存、不参与 Activity 状态恢复，交给原生短链路后立即从输入框清除。
 */
class ZjuTimetableImportActivity : ComponentActivity() {
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "浙江大学"
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val heading = TextView(this).apply {
            text = "从教务网导入  ›  浙江大学"
            textSize = 22f
        }
        val explanation = TextView(this).apply {
            text = "只需填写浙江大学统一身份认证账号和密码。密码仅在本次导入的内存中使用，经官方公钥加密后提交，不会保存或写入日志。"
        }
        usernameInput = EditText(this).apply {
            hint = "统一身份认证账号"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
            isSaveEnabled = false
        }
        passwordInput = EditText(this).apply {
            hint = "统一身份认证密码"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            isSaveEnabled = false
        }
        status = TextView(this).apply { text = "等待开始" }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        startButton = Button(this).apply {
            text = "自动获取课表"
            setOnClickListener { beginImport() }
        }
        val note = TextView(this).apply {
            text = "若统一身份认证要求验证码、账号解锁或二次验证，FocusFlow 不会绕过；请先在浙大认证网页完成后重试。"
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            addView(heading, row())
            addView(explanation, row(top = 12))
            addView(usernameInput, row(top = 18))
            addView(passwordInput, row(top = 8))
            addView(progress, row(top = 18))
            addView(status, row(top = 8))
            addView(startButton, row(top = 14))
            addView(note, row(top = 12))
        }
        setContentView(content)
    }

    private fun beginImport() {
        if (running) return
        val username = usernameInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString()?.toCharArray() ?: CharArray(0)
        passwordInput.text?.clear()
        if (username.isBlank() || password.isEmpty()) {
            password.fill('\u0000')
            status.text = "请填写账号和密码。"
            return
        }

        running = true
        usernameInput.isEnabled = false
        passwordInput.isEnabled = false
        startButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 2
        status.text = "准备连接…"

        ZjuTimetableClient.fetch(
            username = username,
            password = password,
            onProgress = { stage ->
                if (isFinishing || isDestroyed) return@fetch
                progress.progress = stage.percent
                status.text = stage.label
            },
            onComplete = { result ->
                if (isFinishing || isDestroyed) return@fetch
                when (result) {
                    is ZjuTimetableFetchResult.Success -> {
                        progress.progress = 100
                        status.text = "已获取 ${result.schoolYear} ${result.semester}课表，正在导入…"
                        setResult(
                            RESULT_OK,
                            Intent()
                                .putExtra(EXTRA_TIMETABLE_PAYLOAD, result.payload)
                                .putExtra(EXTRA_SCHOOL_YEAR, result.schoolYear)
                                .putExtra(EXTRA_SEMESTER, result.semester)
                        )
                        finish()
                    }
                    is ZjuTimetableFetchResult.Failure -> {
                        running = false
                        usernameInput.isEnabled = true
                        passwordInput.isEnabled = true
                        startButton.isEnabled = true
                        progress.progress = 0
                        status.text = "导入失败：${result.message}"
                        passwordInput.requestFocus()
                    }
                }
            }
        )
    }

    private fun row(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TIMETABLE_PAYLOAD = "zju_timetable_payload"
        const val EXTRA_SCHOOL_YEAR = "zju_school_year"
        const val EXTRA_SEMESTER = "zju_semester"
    }
}
