package com.sakata.focusflow

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

internal enum class ZjuStepVisualState { COMPLETE, ACTIVE, UPCOMING, FAILED }

internal fun zjuStepVisualState(
    step: ZjuImportStage,
    current: ZjuImportStage?,
    running: Boolean,
    failed: Boolean
): ZjuStepVisualState {
    if (current == ZjuImportStage.DONE || (current != null && step.ordinal < current.ordinal)) {
        return ZjuStepVisualState.COMPLETE
    }
    if (step == current) {
        return when {
            failed -> ZjuStepVisualState.FAILED
            running -> ZjuStepVisualState.ACTIVE
            else -> ZjuStepVisualState.UPCOMING
        }
    }
    return ZjuStepVisualState.UPCOMING
}

/**
 * “从教务网导入 → 浙江大学”账号输入页。
 * 密码不保存、不参与 Activity 状态恢复，交给原生短链路后立即从输入状态清除。
 */
class ZjuTimetableImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "浙江大学教务导入"
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        val store = PrototypeStore(this)
        val themeSpec = focusFlowThemeSpec(
            option = store.loadTheme(),
            customColors = store.loadCustomThemeColors(),
            darkMode = store.loadDarkMode()
        )
        val appearance = store.loadAppearance()

        setContent {
            val pageBitmap by produceState<ImageBitmap?>(initialValue = null, appearance.pageImage) {
                if (appearance.hasPageImage) {
                    value = withContext(Dispatchers.IO) {
                        val metrics = resources.displayMetrics
                        AppearanceImages.load(
                            this@ZjuTimetableImportActivity,
                            appearance.pageImage,
                            metrics.widthPixels.coerceIn(1, 1440),
                            metrics.heightPixels.coerceIn(1, 3168)
                        )
                    }
                }
            }
            MaterialTheme(colorScheme = themeSpec.colorScheme) {
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = themeSpec.colorScheme.background.luminance() > 0.5f
                        isAppearanceLightNavigationBars = themeSpec.colorScheme.background.luminance() > 0.5f
                    }
                }

                var username by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var running by remember { mutableStateOf(false) }
                var currentStage by remember { mutableStateOf<ZjuImportStage?>(null) }
                var failure by remember { mutableStateOf<String?>(null) }
                var specifySemester by remember { mutableStateOf(false) }
                val initialYear = remember {
                    Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).let {
                        it.get(Calendar.YEAR) - if (it.get(Calendar.MONTH) < Calendar.AUGUST) 1 else 0
                    }
                }
                var selectedYear by remember { mutableStateOf(initialYear.toString()) }
                var selectedTerm by remember { mutableStateOf(ZjuTerm.FALL_WINTER) }

                fun beginImport() {
                    if (running) return
                    val trimmedUsername = username.trim()
                    val manualSemester = if (specifySemester) {
                        val year = selectedYear.toIntOrNull()
                        if (year == null || year !in 2000..2100) {
                            failure = "请填写有效的学年起始年份，如 2026。"
                            return
                        }
                        ZjuManualSemester(year, selectedTerm)
                    } else null
                    val passwordChars = password.toCharArray()
                    password = ""
                    if (trimmedUsername.isBlank() || passwordChars.isEmpty()) {
                        passwordChars.fill('\u0000')
                        failure = "请填写统一身份认证账号和密码。"
                        return
                    }

                    running = true
                    failure = null
                    currentStage = ZjuImportStage.CONNECTING
                    ZjuTimetableClient.fetch(
                        username = trimmedUsername,
                        password = passwordChars,
                        manualSemester = manualSemester,
                        onProgress = { stage ->
                            if (!isFinishing && !isDestroyed) currentStage = stage
                        },
                        onComplete = { result ->
                            if (isFinishing || isDestroyed) return@fetch
                            when (result) {
                                is ZjuTimetableFetchResult.Success -> {
                                    currentStage = ZjuImportStage.DONE
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
                                    failure = result.message
                                }
                            }
                        }
                    )
                }

                val glassBackdropState = remember(appearance.effectiveCardMaterial) {
                    if (appearance.effectiveCardMaterial.samplesPageBackdrop) HazeState() else null
                }
                CompositionLocalProvider(
                    LocalAppearance provides appearance,
                    LocalBackdropBitmap provides pageBitmap,
                    LocalGlassBackdropState provides glassBackdropState
                ) {
                    CompositionLocalProvider(LocalContentColor provides pageBodyContentColor()) {
                        Box(Modifier.fillMaxSize().background(themeSpec.colorScheme.background)) {
                            Box(
                                Modifier.fillMaxSize()
                                    .then(if (glassBackdropState != null) {
                                        Modifier.hazeSource(glassBackdropState, zIndex = 0f, key = "import-background")
                                    } else Modifier)
                                    .appearanceBackdrop(
                                        appearance,
                                        themeSpec.colorScheme,
                                        pageBitmap,
                                        imageLuminance = rememberImageLuminance(pageBitmap)
                                    )
                            )
                            ZjuTimetableImportScreen(
                                username = username,
                                onUsernameChange = {
                                    username = it
                                    if (failure != null) failure = null
                                },
                                password = password,
                                onPasswordChange = {
                                    password = it
                                    if (failure != null) failure = null
                                },
                                running = running,
                                currentStage = currentStage,
                                failure = failure,
                                specifySemester = specifySemester,
                                onSpecifySemesterChange = { specifySemester = it; failure = null },
                                selectedYear = selectedYear,
                                onSelectedYearChange = { selectedYear = it.filter(Char::isDigit).take(4); failure = null },
                                selectedTerm = selectedTerm,
                                onSelectedTermChange = { selectedTerm = it },
                                onBack = { finish() },
                                onStart = ::beginImport
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TIMETABLE_PAYLOAD = "zju_timetable_payload"
        const val EXTRA_SCHOOL_YEAR = "zju_school_year"
        const val EXTRA_SEMESTER = "zju_semester"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZjuTimetableImportScreen(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    running: Boolean,
    currentStage: ZjuImportStage?,
    failure: String?,
    specifySemester: Boolean,
    onSpecifySemesterChange: (Boolean) -> Unit,
    selectedYear: String,
    onSelectedYearChange: (String) -> Unit,
    selectedTerm: ZjuTerm,
    onSelectedTermChange: (ZjuTerm) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var showPassword by remember { mutableStateOf(false) }
    val canStart = username.isNotBlank() && password.isNotBlank() && !running &&
        (!specifySemester || (selectedYear.toIntOrNull() ?: 0) in 2000..2100)

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("浙江大学教务导入", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !running) {
                        Text("‹", fontSize = 32.sp, lineHeight = 32.sp)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ImportHeader()
            CredentialCard(
                username = username,
                onUsernameChange = onUsernameChange,
                password = password,
                onPasswordChange = onPasswordChange,
                showPassword = showPassword,
                onTogglePassword = { showPassword = !showPassword },
                running = running,
                canStart = canStart,
                specifySemester = specifySemester,
                onSpecifySemesterChange = onSpecifySemesterChange,
                selectedYear = selectedYear,
                onSelectedYearChange = onSelectedYearChange,
                selectedTerm = selectedTerm,
                onSelectedTermChange = onSelectedTermChange,
                onStart = {
                    focusManager.clearFocus()
                    onStart()
                }
            )
            ImportProgressCard(
                currentStage = currentStage,
                running = running,
                failed = failure != null,
                specifySemester = specifySemester
            )
            if (failure != null) FailureCard(failure)
            ImportBoundaryNote()
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ImportHeader() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "浙",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "从教务网导入",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "自动读取当前学期，也可指定学期",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CredentialCard(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    running: Boolean,
    canStart: Boolean,
    specifySemester: Boolean,
    onSpecifySemesterChange: (Boolean) -> Unit,
    selectedYear: String,
    onSelectedYearChange: (String) -> Unit,
    selectedTerm: ZjuTerm,
    onSelectedTermChange: (ZjuTerm) -> Unit,
    onStart: () -> Unit
) {
    FocusCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("统一身份认证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !running,
                singleLine = true,
                label = { Text("账号") },
                placeholder = { Text("学号或统一身份认证账号") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                )
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !running,
                singleLine = true,
                label = { Text("密码") },
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { if (canStart) onStart() }),
                trailingIcon = {
                    TextButton(onClick = onTogglePassword, enabled = !running) {
                        Text(if (showPassword) "隐藏" else "显示")
                    }
                }
            )
            Text("选择学期", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !specifySemester,
                    onClick = { onSpecifySemesterChange(false) },
                    enabled = !running,
                    label = { Text("自动读取") }
                )
                FilterChip(
                    selected = specifySemester,
                    onClick = { onSpecifySemesterChange(true) },
                    enabled = !running,
                    label = { Text("指定学期") }
                )
            }
            if (specifySemester) {
                OutlinedTextField(
                    value = selectedYear,
                    onValueChange = onSelectedYearChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running,
                    singleLine = true,
                    label = { Text("学年起始年份") },
                    supportingText = { Text("例如 2026 表示 2026—2027 学年") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZjuTerm.entries.forEach { term ->
                        FilterChip(
                            selected = selectedTerm == term,
                            onClick = { onSelectedTermChange(term) },
                            enabled = !running,
                            label = { Text(term.display) }
                        )
                    }
                }
                Text(
                    "指定学期会跳过读取当前学期页面，仍需登录教务网并下载所选课表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "凭据仅用于本次导入",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "密码提交后立即从输入框清除，账号、密码与会话均不保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                Text(if (running) "正在获取…" else "获取课表", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ImportProgressCard(
    currentStage: ZjuImportStage?,
    running: Boolean,
    failed: Boolean,
    specifySemester: Boolean
) {
    val stages = remember {
        listOf(
            ZjuImportStage.CONNECTING to "连接统一身份认证",
            ZjuImportStage.ENCRYPTING to "获取参数并加密凭据",
            ZjuImportStage.AUTHENTICATING to "验证账号",
            ZjuImportStage.ESTABLISHING_SESSION to "建立教务会话",
            ZjuImportStage.LOADING_SEMESTER to "读取当前学期",
            ZjuImportStage.FETCHING_TIMETABLE to "获取课表数据",
            ZjuImportStage.PARSING to "解析并核对课程"
        )
    }

    FocusCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("导入进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${currentStage?.percent ?: 0}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = { (currentStage?.percent ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            stages.forEachIndexed { index, (stage, label) ->
                ImportStepRow(
                    number = index + 1,
                    label = if (stage == ZjuImportStage.LOADING_SEMESTER && specifySemester) "使用指定学期" else label,
                    state = zjuStepVisualState(stage, currentStage, running, failed)
                )
                if (index != stages.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 36.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportStepRow(number: Int, label: String, state: ZjuStepVisualState) {
    val markerColor = when (state) {
        ZjuStepVisualState.COMPLETE -> MaterialTheme.colorScheme.primary
        ZjuStepVisualState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        ZjuStepVisualState.FAILED -> MaterialTheme.colorScheme.errorContainer
        ZjuStepVisualState.UPCOMING -> MaterialTheme.colorScheme.surfaceVariant
    }
    val markerTextColor = when (state) {
        ZjuStepVisualState.COMPLETE -> MaterialTheme.colorScheme.onPrimary
        ZjuStepVisualState.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        ZjuStepVisualState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        ZjuStepVisualState.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val labelColor = when (state) {
        ZjuStepVisualState.ACTIVE -> MaterialTheme.colorScheme.primary
        ZjuStepVisualState.FAILED -> MaterialTheme.colorScheme.error
        ZjuStepVisualState.COMPLETE -> MaterialTheme.colorScheme.onSurface
        ZjuStepVisualState.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(markerColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (state == ZjuStepVisualState.COMPLETE) "✓" else number.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = markerTextColor
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = labelColor)
            if (state == ZjuStepVisualState.ACTIVE) {
                Text("正在处理…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else if (state == ZjuStepVisualState.FAILED) {
                Text("此步骤未完成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun FailureCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("导入未完成", fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Text("账号已保留；重新输入密码即可再次尝试。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImportBoundaryNote() {
    Text(
        "若认证要求验证码、账号解锁或二次验证，FocusFlow 不会绕过。请先在浙江大学认证网页完成处理后再试。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
