package com.sakata.focusflow

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.LaunchedEffect
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
    private val importViewModel: ZjuImportViewModel by lazy {
        ViewModelProvider(this)[ZjuImportViewModel::class.java]
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            // 真正离开页面：作废待确认会话、中断在途请求（含已确认的课表请求——本实例的结果
            // 已无处投递，继续跑只会白占连接与已认证会话）。
            importViewModel.cancelAllWaiting()
        }
        super.onDestroy()
    }

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

                val importState = importViewModel
                var password by remember { mutableStateOf("") }

                // 终态结果由 ViewModel（跨配置变更保留）投递；只有存活的新实例才 setResult/finish，
                // 因此旋转屏幕时旧实例销毁不会让完成状态丢失。
                LaunchedEffect(importState.outcome) {
                    val outcome = importState.outcome ?: return@LaunchedEffect
                    // 先取走再处理：旋转导致本实例被取消重放时不会重复 finish，
                    // 成功结果也不会被同一实例重复消费。
                    importState.consumeOutcome()
                    when (outcome) {
                        is ZjuSemesterOptionsResult.Success -> Unit
                        is ZjuSemesterOptionsResult.Failure -> importState.failure = outcome.message
                        is ZjuTimetableFetchResult.Success -> {
                            setResult(
                                RESULT_OK,
                                Intent()
                                    .putExtra(EXTRA_TIMETABLE_PAYLOAD, outcome.payload)
                                    .putExtra(EXTRA_SCHOOL_YEAR, outcome.schoolYear)
                                    .putExtra(EXTRA_SEMESTER, outcome.semester)
                                    .putExtra(EXTRA_SCHOOL_YEAR_CODE, outcome.schoolYearCode)
                                    .putExtra(EXTRA_TERM_CODE, outcome.termCode)
                            )
                            finish()
                        }
                        is ZjuTimetableFetchResult.Failure -> importState.applyFetchFailure(outcome)
                    }
                }

                fun beginImport() {
                    if (importState.running) return
                    val trimmedUsername = importState.username.trim()
                    val passwordChars = password.toCharArray()
                    password = ""
                    if (trimmedUsername.isBlank() || passwordChars.isEmpty()) {
                        passwordChars.fill('\u0000')
                        importState.failure = "请填写统一身份认证账号和密码。"
                        return
                    }
                    importState.startLogin(trimmedUsername, passwordChars)
                }

                fun confirmImport() {
                    if (importState.running) return
                    val ready = importState.options ?: return
                    val yearValue = importState.selectedYearValue ?: return
                    val termValue = importState.selectedTermValue ?: return
                    importState.startConfirm(ready, yearValue, termValue)
                }

                fun resetToLogin() {
                    if (importState.running) return
                    importState.reset()
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
                                username = importState.username,
                                onUsernameChange = {
                                    importState.username = it
                                    if (importState.failure != null) importState.failure = null
                                },
                                password = password,
                                onPasswordChange = {
                                    password = it
                                    if (importState.failure != null) importState.failure = null
                                },
                                running = importState.running,
                                currentStage = importState.currentStage,
                                failure = importState.failure,
                                options = importState.options,
                                selectedYearValue = importState.selectedYearValue,
                                onSelectedYearValueChange = {
                                    importState.selectedYearValue = it
                                    importState.failure = null
                                },
                                selectedTermValue = importState.selectedTermValue,
                                onSelectedTermValueChange = {
                                    importState.selectedTermValue = it
                                    importState.failure = null
                                },
                                onBack = { finish() },
                                onStart = ::beginImport,
                                onConfirm = ::confirmImport,
                                onReset = ::resetToLogin
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
        const val EXTRA_SCHOOL_YEAR_CODE = "zju_school_year_code"
        const val EXTRA_TERM_CODE = "zju_term_code"
    }
}

/** 跨配置变更保留学期选项、句柄与进行中状态；进程重建时随 ViewModel 一起丢失，需重新登录。 */
internal class ZjuImportViewModel(
    transportFactory: () -> ZjuHttpClient = { ZjuTimetableClient.defaultTransportFactory() }
) : ViewModel() {
    /** 本次登录使用的传输实例；离页时只中断它，不影响同进程其他实例的请求。 */
    private var activeTransport: ZjuHttpClient? = null
    private val transportFactory: () -> ZjuHttpClient = {
        transportFactory().also { activeTransport = it }
    }
    var username by mutableStateOf("")
    var running by mutableStateOf(false)
    var currentStage by mutableStateOf<ZjuImportStage?>(null)
    var failure by mutableStateOf<String?>(null)
    var options by mutableStateOf<ZjuSemesterOptionsResult.Success?>(null)
    var selectedYearValue by mutableStateOf<String?>(null)
    var selectedTermValue by mutableStateOf<String?>(null)
    var pendingSession: ZjuTimetableSessionHandle? = null

    /**
     * 终态结果（成功或失败）。状态与回调都不再依赖某个 Activity 实例存活，
     * 旋转后由新的实例观察并执行 setResult/finish，因此 running 不会卡住。
     */
    var outcome by mutableStateOf<Any?>(null)
        private set

    /** 第一步：登录并读取学年/学期选项；结果写入 ViewModel，不触碰 Activity。 */
    fun startLogin(username: String, password: CharArray) {
        if (running) return
        running = true
        failure = null
        outcome = null
        options = null
        selectedYearValue = null
        selectedTermValue = null
        pendingSession = null
        currentStage = ZjuImportStage.CONNECTING
        try {
            ZjuTimetableClient.beginSession(
                username = username,
                password = password,
                onProgress = { stage -> dispatch { currentStage = stage } },
                transportFactory = transportFactory,
                onComplete = { result ->
                    dispatch {
                        running = false
                        when (result) {
                            is ZjuSemesterOptionsResult.Success -> {
                                currentStage = null
                                pendingSession = result.handle
                                options = result
                                selectedYearValue = ZjuTimetableClient.selectAutoOption(result.yearOptions)?.value
                                selectedTermValue = ZjuTimetableClient.selectAutoOption(result.termOptions)?.value
                                outcome = result
                            }
                            is ZjuSemesterOptionsResult.Failure -> {
                                // 保留失败时的 currentStage，进度卡才能把失败步骤标红（不要清空）。
                                failure = result.message
                                outcome = result
                            }
                        }
                    }
                }
            )
        } catch (error: Throwable) {
            // 客户端同步抛错时也必须复位，不能让按钮停在“正在获取…”。
            password.fill('\u0000')
            running = false
            currentStage = null
            failure = error.message?.take(240) ?: "教务导入启动失败，请稍后重试。"
        }
    }

    /** 第二步：按用户确认的原始 value 用同一会话请求课表。 */
    fun startConfirm(
        ready: ZjuSemesterOptionsResult.Success,
        yearValue: String,
        termValue: String
    ) {
        if (running) return
        running = true
        failure = null
        outcome = null
        currentStage = ZjuImportStage.FETCHING_TIMETABLE
        try {
            ZjuTimetableClient.confirmSelectedSemester(
                handle = ready.handle,
                yearValue = yearValue,
                termValue = termValue,
                onProgress = { stage -> dispatch { currentStage = stage } },
                onComplete = { result ->
                    dispatch {
                        when (result) {
                            is ZjuTimetableFetchResult.Success -> {
                                running = false
                                currentStage = ZjuImportStage.DONE
                                pendingSession = null
                                outcome = result
                            }
                            is ZjuTimetableFetchResult.Failure -> {
                                // 保留失败时的 currentStage（与第一阶段一致）。
                                running = false
                                applyFetchFailure(result)
                                outcome = result
                            }
                        }
                    }
                }
            )
        } catch (error: Throwable) {
            // 客户端同步抛错时也必须复位，不能让按钮停在“正在获取…”。
            running = false
            failure = error.message?.take(240) ?: "课表导入启动失败，请稍后重试。"
        }
    }

    /** 终态已被 Activity 处理（失败已展示或已 setResult/finish），清掉避免重复消费。 */
    fun consumeOutcome() {
        outcome = null
    }

    /**
     * 第二阶段失败的统一处理：普通失败只显示文案；会话已失效（登录失效/验证码）时还必须清掉
     * 旧学期选项，否则按钮仍是可点的“导入所选学期”，用户会做一次注定失败的重复请求。
     * 两条终态路径（onComplete 直写、LaunchedEffect 观察 outcome）都走这里，避免行为分叉。
     */
    fun applyFetchFailure(result: ZjuTimetableFetchResult.Failure) {
        failure = result.message
        if (!result.sessionInvalid) return
        options = null
        pendingSession = null
        selectedYearValue = null
        selectedTermValue = null
        currentStage = null
        // 解析层的文案自己已经说了"重新登录/重新认证"时不再追加，避免出现重复句或自相矛盾。
        failure = if (result.message.contains("重新登录") || result.message.contains("重新完成统一身份认证")) {
            result.message
        } else {
            "${result.message}请重新登录后再导入。"
        }
    }

    /** 用户主动重新登录：作废待确认会话并回到账号输入。 */
    fun reset() {
        cancelAllWaiting()
        options = null
        selectedYearValue = null
        selectedTermValue = null
        failure = null
        currentStage = null
        outcome = null
    }

    /** 离开页面：作废待确认会话、中断在途请求（含已确认的课表请求；结果不再有消费者）。 */
    fun cancelAllWaiting() {
        pendingSession?.let { ZjuTimetableClient.cancelSession(it) }
        pendingSession = null
        // 第一阶段的在途请求还没有句柄，必须无条件中断它（此前按 `!running` 判断会让该调用永不执行）。
        activeTransport?.let { ZjuTimetableClient.cancelTransport(it) }
        activeTransport = null
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) action()
        // 投递失败时直接执行，保证 running 不会因为主 looper 退出而永远卡住。
        else if (!runCatching { Handler(Looper.getMainLooper()).post(action) }.getOrDefault(false)) action()
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
    options: ZjuSemesterOptionsResult.Success?,
    selectedYearValue: String?,
    onSelectedYearValueChange: (String) -> Unit,
    selectedTermValue: String?,
    onSelectedTermValueChange: (String) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var showPassword by remember { mutableStateOf(false) }
    val optionsReady = options != null
    val canStart = username.isNotBlank() && password.isNotBlank() && !running && !optionsReady
    val canConfirm = selectedYearValue != null && selectedTermValue != null && !running

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
                optionsReady = optionsReady,
                canStart = canStart,
                canConfirm = canConfirm,
                onStart = {
                    focusManager.clearFocus()
                    onStart()
                },
                onConfirm = {
                    focusManager.clearFocus()
                    onConfirm()
                },
                onReset = onReset
            )
            val readyOptions = options
            if (readyOptions != null) {
                SemesterOptionsCard(
                    options = readyOptions,
                    selectedYearValue = selectedYearValue,
                    onSelectedYearValueChange = onSelectedYearValueChange,
                    selectedTermValue = selectedTermValue,
                    onSelectedTermValueChange = onSelectedTermValueChange,
                    running = running
                )
            }
            ImportProgressCard(
                currentStage = currentStage,
                running = running,
                failed = failure != null
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
                "登录后读取学期选项，确认无误再导入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CredentialCard(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    running: Boolean,
    optionsReady: Boolean,
    canStart: Boolean,
    canConfirm: Boolean,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit
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
                enabled = !running && !optionsReady,
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
                enabled = !running && !optionsReady,
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
            if (optionsReady) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("学期选项已读取", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onReset, enabled = !running) {
                        Text("重新登录")
                    }
                }
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
                onClick = if (optionsReady) onConfirm else onStart,
                enabled = if (optionsReady) canConfirm else canStart,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                Text(
                    when {
                        running -> "正在获取…"
                        optionsReady -> "导入所选学期"
                        else -> "登录并读取学期选项"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

internal fun zjuSemesterOptionLabels(options: List<ZjuSemesterOption>): List<String> {
    val totals = options.groupingBy { it.text }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return options.map { option ->
        val index = (seen[option.text] ?: 0) + 1
        seen[option.text] = index
        if ((totals[option.text] ?: 0) > 1) "${option.text}（$index）" else option.text
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SemesterOptionsCard(
    options: ZjuSemesterOptionsResult.Success,
    selectedYearValue: String?,
    onSelectedYearValueChange: (String) -> Unit,
    selectedTermValue: String?,
    onSelectedTermValueChange: (String) -> Unit,
    running: Boolean
) {
    val yearLabels = zjuSemesterOptionLabels(options.yearOptions)
    val termLabels = zjuSemesterOptionLabels(options.termOptions)

    FocusCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("确认学年与学期", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "以下选项来自教务页面，导入将逐字使用所选值。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("学年", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.yearOptions.forEachIndexed { index, option ->
                    FilterChip(
                        selected = selectedYearValue == option.value,
                        onClick = { onSelectedYearValueChange(option.value) },
                        enabled = !running,
                        label = { Text(yearLabels[index]) }
                    )
                }
            }
            Text("学期", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.termOptions.forEachIndexed { index, option ->
                    FilterChip(
                        selected = selectedTermValue == option.value,
                        onClick = { onSelectedTermValueChange(option.value) },
                        enabled = !running,
                        label = { Text(termLabels[index]) }
                    )
                }
            }
            if (selectedYearValue == null || selectedTermValue == null) {
                Text(
                    "请选择学年与学期后再导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ImportProgressCard(
    currentStage: ZjuImportStage?,
    running: Boolean,
    failed: Boolean
) {
    val stages = remember {
        listOf(
            ZjuImportStage.CONNECTING to "连接统一身份认证",
            ZjuImportStage.ENCRYPTING to "获取参数并加密凭据",
            ZjuImportStage.AUTHENTICATING to "验证账号",
            ZjuImportStage.ESTABLISHING_SESSION to "建立教务会话",
            ZjuImportStage.LOADING_SEMESTER to "读取学期选项",
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
                    label = label,
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
            Text("账号已保留。读取学期选项失败可重新输入密码再试；导入课表失败可直接再点“导入所选学期”，若提示登录失效请改点“重新登录”。", style = MaterialTheme.typography.bodySmall)
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
