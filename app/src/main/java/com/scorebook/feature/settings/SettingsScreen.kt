package com.scorebook.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.scorebook.core.data.BackupManager
import com.scorebook.core.data.SettingsRepository
import com.scorebook.core.model.AppSettings
import com.scorebook.core.model.FaceGestureMode
import com.scorebook.core.model.ViewMode
import com.scorebook.core.mvi.MviViewModel
import com.scorebook.core.mvi.UiEffect
import com.scorebook.core.mvi.UiIntent
import com.scorebook.core.mvi.UiState
import com.scorebook.core.storage.LibraryFiles
import com.scorebook.ui.components.ConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

data class SettingsState(
    val settings: AppSettings = AppSettings(),
    val storageBytes: Long = 0,
    val busy: Boolean = false,
    val confirmRestore: Uri? = null,
) : UiState

sealed interface SettingsIntent : UiIntent {
    data class Update(val transform: (AppSettings) -> AppSettings) : SettingsIntent
    data class Backup(val uri: Uri) : SettingsIntent
    data class RequestRestore(val uri: Uri?) : SettingsIntent
    data object ConfirmRestore : SettingsIntent
}

sealed interface SettingsEffect : UiEffect {
    data class Message(val text: String) : SettingsEffect
    data object Restart : SettingsEffect
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val backup: BackupManager,
    private val files: LibraryFiles,
) : MviViewModel<SettingsState, SettingsIntent, SettingsEffect>(SettingsState()) {

    init {
        viewModelScope.launch { repo.settings.collect { setState { copy(settings = it) } } }
        refreshStorage()
    }

    override fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.Update -> viewModelScope.launch { repo.update(intent.transform) }
            is SettingsIntent.Backup -> viewModelScope.launch {
                setState { copy(busy = true) }
                backup.export(intent.uri)
                    .onSuccess { sendEffect(SettingsEffect.Message("백업 파일을 저장했습니다")) }
                    .onFailure { sendEffect(SettingsEffect.Message("백업 실패: ${it.message}")) }
                setState { copy(busy = false) }
            }
            is SettingsIntent.RequestRestore -> setState { copy(confirmRestore = intent.uri) }
            SettingsIntent.ConfirmRestore -> {
                val uri = currentState.confirmRestore ?: return
                setState { copy(confirmRestore = null, busy = true) }
                viewModelScope.launch {
                    backup.restore(uri)
                        .onSuccess { sendEffect(SettingsEffect.Restart) }
                        .onFailure {
                            setState { copy(busy = false) }
                            sendEffect(SettingsEffect.Message("복원 실패: ${it.message}"))
                        }
                }
            }
        }
    }

    private fun refreshStorage() {
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) { files.root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            setState { copy(storageBytes = size) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state.settings
    val snackbar = remember { SnackbarHostState() }
    val activity = LocalActivity.current
    fun update(t: (AppSettings) -> AppSettings) = viewModel.onIntent(SettingsIntent.Update(t))

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.onIntent(SettingsIntent.Backup(uri))
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onIntent(SettingsIntent.RequestRestore(uri))
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SettingsEffect.Message -> launch { snackbar.showSnackbar(effect.text) }
                SettingsEffect.Restart -> activity?.let { a ->
                    val intent = a.packageManager.getLaunchIntentForPackage(a.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    if (intent != null) a.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.widthIn(max = 720.dp).padding(bottom = 32.dp)) {
                Section("악보 보기")
                Text("기본 보기 방식", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                listOf(
                    ViewMode.SINGLE to "한 페이지",
                    ViewMode.TWO_PAGE to "두 페이지 (가로 화면)",
                    ViewMode.HALF to "반 페이지 넘김",
                    ViewMode.VERTICAL to "세로 스크롤",
                ).forEach { (mode, label) ->
                    RadioRow(label, s.viewMode == mode) { update { it.copy(viewMode = mode) } }
                }
                SwitchRow("가로 화면에서 두 페이지로 보기", "한 페이지 모드일 때 태블릿을 가로로 돌리면 자동으로 펼쳐 봅니다", s.twoPageInLandscape) {
                    update { st -> st.copy(twoPageInLandscape = it) }
                }
                SwitchRow("페이지 넘김 애니메이션", null, s.pageTurnAnimation) { update { st -> st.copy(pageTurnAnimation = it) } }
                SwitchRow("마지막으로 본 페이지 기억", null, s.rememberLastPage) { update { st -> st.copy(rememberLastPage = it) } }
                SwitchRow("여백 자동 자르기", "악보 주변 여백을 잘라 음표를 더 크게 보여 줍니다", s.autoCropMargins) {
                    update { st -> st.copy(autoCropMargins = it) }
                }

                Section("페이지 넘기기")
                SwitchRow("화면 가장자리 탭으로 넘기기", "왼쪽 탭 = 이전, 오른쪽 탭 = 다음, 가운데 탭 = 메뉴", s.tapZonesEnabled) {
                    update { st -> st.copy(tapZonesEnabled = it) }
                }
                SliderRow("탭 영역 너비", "${(s.tapZoneFraction * 100).roundToInt()}%", s.tapZoneFraction, 0.15f..0.45f) {
                    update { st -> st.copy(tapZoneFraction = it) }
                }
                SwitchRow("볼륨 버튼으로 넘기기", "블루투스 페달은 별도 설정 없이 바로 동작합니다 (PageUp/PageDown, 방향키, 스페이스)", s.volumeKeysTurnPages) {
                    update { st -> st.copy(volumeKeysTurnPages = it) }
                }
                SliderRow("자동 넘김 간격", "${s.autoTurnSeconds}초", s.autoTurnSeconds.toFloat(), 5f..120f) {
                    update { st -> st.copy(autoTurnSeconds = it.roundToInt()) }
                }
                SliderRow("자동 스크롤 속도 (세로 모드)", "${s.autoScrollSpeed.roundToInt()}", s.autoScrollSpeed, 5f..150f) {
                    update { st -> st.copy(autoScrollSpeed = it) }
                }

                Section("펜")
                SwitchRow("손바닥 무시 (팜 리젝션)", "펜이 화면 근처에 있을 때 손가락/손바닥 터치를 무시합니다", s.palmRejection) {
                    update { st -> st.copy(palmRejection = it) }
                }
                SwitchRow("펜 버튼을 누르면 지우개", "S펜 등 측면 버튼을 누른 채 문지르면 지웁니다", s.stylusButtonErases) {
                    update { st -> st.copy(stylusButtonErases = it) }
                }

                Section("얼굴 제스처")
                Text(
                    "전면 카메라로 얼굴 움직임을 인식해 손을 쓰지 않고 페이지를 넘깁니다. 모든 처리는 기기 안에서만 이루어집니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                listOf(
                    FaceGestureMode.HEAD_TURN to "고개 돌리기 (오른쪽 = 다음, 왼쪽 = 이전)",
                    FaceGestureMode.SMILE to "미소 짓기 (다음)",
                    FaceGestureMode.MOUTH_OPEN to "입 벌리기 (다음, 관악기 연주자 비추천)",
                ).forEach { (mode, label) ->
                    RadioRow(label, s.faceGestureMode == mode) { update { it.copy(faceGestureMode = mode) } }
                }
                SliderRow("민감도", "${(s.faceGestureSensitivity * 100).roundToInt()}%", s.faceGestureSensitivity, 0f..1f) {
                    update { st -> st.copy(faceGestureSensitivity = it) }
                }

                Section("백업")
                Text(
                    "서버를 쓰지 않으므로 모든 악보와 필기는 이 기기 안에만 있습니다. 기기를 바꾸거나 앱을 지우기 전에 백업 파일을 만들어 두세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ListItem(
                    headlineContent = { Text("백업 파일 만들기") },
                    supportingContent = { Text("악보·필기·녹음·설정을 zip 하나로 저장") },
                    modifier = Modifier.clickable(enabled = !state.busy) {
                        backupLauncher.launch("Hoon's뷰어 백업 " + SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date()) + ".zip")
                    },
                )
                ListItem(
                    headlineContent = { Text("백업에서 복원") },
                    supportingContent = { Text("현재 데이터를 백업 내용으로 바꾸고 앱을 다시 시작합니다") },
                    modifier = Modifier.clickable(enabled = !state.busy) {
                        restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("사용 중인 저장 공간") },
                    trailingContent = { Text(formatBytes(state.storageBytes)) },
                )
                ListItem(
                    headlineContent = { Text("앱 버전") },
                    trailingContent = { Text("V${com.scorebook.BuildConfig.VERSION_NAME} (${com.scorebook.BuildConfig.VERSION_CODE})") },
                )
            }
        }
    }

    if (state.confirmRestore != null) {
        ConfirmDialog(
            title = "백업에서 복원",
            message = "지금 기기에 있는 악보와 필기가 모두 백업 파일의 내용으로 바뀝니다. 계속할까요?",
            confirmText = "복원",
            destructive = true,
            onConfirm = { viewModel.onIntent(SettingsIntent.ConfirmRestore) },
            onDismiss = { viewModel.onIntent(SettingsIntent.RequestRestore(null)) },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%d KB".format(bytes / 1024)
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SliderRow(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(title, modifier = Modifier.weight(1f))
            Text(valueLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range)
    }
}
