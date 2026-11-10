package com.scorebook.core.face

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.scorebook.core.model.FaceGestureMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 전면 카메라 + ML Kit 얼굴 인식(기기 내 번들 모델, 네트워크 불필요)으로 손 없이 페이지 넘기기.
 * - 고개 돌리기: 책장을 넘기듯 왼쪽 = 다음, 오른쪽 = 이전.
 *   화면을 보며 잠깐 멈춘 뒤(응시) 빠르게 돌렸을 때만 넘긴다. 그냥 두리번거리거나 천천히 돌린 것은 무시.
 * - 입 벌리기: 다음
 * 제스처 후 중립 자세로 돌아와야 다시 동작(연속 넘김 방지) + 쿨다운.
 */
class FaceGestureController(private val context: Context) {

    /** READY = 고개 돌리기에서 응시가 확인되어 지금 돌리면 넘어가는 상태 */
    enum class Status { OFF, SEARCHING, TRACKING, READY }

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var detector: FaceDetector? = null
    private var executor: ExecutorService? = null
    private var armed = true
    private var lastTrigger = 0L

    // 고개 돌리기: 응시(화면을 보며 멈춘 자세)를 기준으로 삼아 거기서 얼마나 빨리 돌렸는지를 본다
    private var lastSeen = 0L
    private var steadySince = 0L
    private var anchorYaw = 0f
    private var anchorPitch = 0f
    private var hasBaseline = false
    private var baselineYaw = 0f
    private var turnStart = 0L

    fun start(
        owner: LifecycleOwner,
        mode: FaceGestureMode,
        sensitivity: Float,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
    ) {
        stop()
        // 고개 돌리기는 응시부터 해야 하고, 입 벌리기는 바로 동작한다
        resetHeadTurn()
        armed = mode != FaceGestureMode.HEAD_TURN
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(
                if (mode == FaceGestureMode.MOUTH_OPEN) FaceDetectorOptions.CONTOUR_MODE_ALL
                else FaceDetectorOptions.CONTOUR_MODE_NONE
            )
            .setMinFaceSize(0.25f)
            .build()
        val det = FaceDetection.getClient(options)
        detector = det
        val exec = Executors.newSingleThreadExecutor()
        executor = exec
        _status.value = Status.SEARCHING

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(exec) { proxy -> analyze(proxy, det, mode, sensitivity, onNext, onPrevious) }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            }.onFailure { _status.value = Status.OFF }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        provider = null
        detector?.close()
        detector = null
        executor?.shutdown()
        executor = null
        _status.value = Status.OFF
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(
        proxy: ImageProxy,
        det: FaceDetector,
        mode: FaceGestureMode,
        sensitivity: Float,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
    ) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        det.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    _status.value = Status.SEARCHING
                } else {
                    if (mode != FaceGestureMode.HEAD_TURN) _status.value = Status.TRACKING
                    handle(face, mode, sensitivity, onNext, onPrevious)
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun handle(face: Face, mode: FaceGestureMode, sensitivity: Float, onNext: () -> Unit, onPrevious: () -> Unit) {
        val s = sensitivity.coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()
        val coolingDown = now - lastTrigger < COOLDOWN_MS
        when (mode) {
            FaceGestureMode.HEAD_TURN -> {
                val threshold = 32f - 16f * s
                val yaw = face.headEulerAngleY
                val pitch = face.headEulerAngleX
                // 얼굴을 놓쳤다가 다시 잡으면 처음부터 다시 응시해야 한다
                if (now - lastSeen > FACE_LOST_MS) resetHeadTurn()
                lastSeen = now
                if (abs(yaw - anchorYaw) > STEADY_YAW_DEG || abs(pitch - anchorPitch) > STEADY_PITCH_DEG) {
                    anchorYaw = yaw
                    anchorPitch = pitch
                    steadySince = now
                } else if (now - steadySince >= GAZE_DWELL_MS) {
                    // 응시 판정: 화면을 보던 자세 그대로 고개가 잠깐 멈춰 있어야 넘길 준비가 된다.
                    // 고개를 돌린 채 멈춰 있는 것(다른 곳을 보는 중)은 준비로 치지 않는다.
                    val facingScreen = if (hasBaseline) abs(anchorYaw - baselineYaw) < threshold * 0.4f
                    else abs(anchorYaw) < FRONTAL_YAW_DEG
                    if (facingScreen && (armed || !coolingDown)) {
                        armed = true
                        hasBaseline = true
                        baselineYaw = anchorYaw
                        turnStart = 0L
                    }
                }
                if (armed) {
                    val turn = yaw - baselineYaw
                    if (abs(turn) < threshold * 0.4f) {
                        turnStart = 0L
                    } else {
                        if (turnStart == 0L) turnStart = now
                        when {
                            // 천천히 돌아간 고개는 넘기려는 게 아니라 그냥 움직인 것
                            now - turnStart > TURN_WINDOW_MS -> armed = false
                            // 책장을 넘기듯: 왼쪽으로 돌리면 다음, 오른쪽으로 돌리면 이전
                            turn > threshold -> fire(now, onNext)
                            turn < -threshold -> fire(now, onPrevious)
                        }
                    }
                }
                _status.value = if (armed) Status.READY else Status.TRACKING
            }
            FaceGestureMode.MOUTH_OPEN -> {
                val upper = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points ?: return
                val lower = face.getContour(FaceContour.LOWER_LIP_TOP)?.points ?: return
                if (upper.isEmpty() || lower.isEmpty()) return
                val gap = abs(lower[lower.size / 2].y - upper[upper.size / 2].y) / face.boundingBox.height()
                val threshold = 0.13f - 0.06f * s
                if (gap < threshold * 0.4f) armed = true
                if (armed && !coolingDown && gap > threshold) fire(now, onNext)
            }
        }
    }

    private fun fire(now: Long, action: () -> Unit) {
        armed = false
        lastTrigger = now
        ContextCompat.getMainExecutor(context).execute(action)
    }

    private fun resetHeadTurn() {
        armed = false
        hasBaseline = false
        steadySince = 0L
        turnStart = 0L
        // 다음 프레임에서 기준 자세를 새로 잡도록
        anchorYaw = Float.MAX_VALUE
        anchorPitch = Float.MAX_VALUE
    }

    companion object {
        private const val COOLDOWN_MS = 900L
        /** 이 시간 동안 화면을 보며 멈춰 있어야 응시로 본다 */
        private const val GAZE_DWELL_MS = 400L
        /** 응시 자세에서 벗어나기 시작해 이 시간 안에 끝까지 돌려야 넘김으로 본다 */
        private const val TURN_WINDOW_MS = 700L
        private const val FACE_LOST_MS = 600L
        private const val STEADY_YAW_DEG = 6f
        private const val STEADY_PITCH_DEG = 8f
        /** 처음 기준 자세를 잡을 때 화면 정면으로 인정하는 좌우 범위 */
        private const val FRONTAL_YAW_DEG = 20f
    }
}
