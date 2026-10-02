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
 * - 고개 돌리기: 오른쪽 = 다음, 왼쪽 = 이전
 * - 미소 / 입 벌리기: 다음
 * 제스처 후 중립 자세로 돌아와야 다시 동작(연속 넘김 방지) + 쿨다운.
 */
class FaceGestureController(private val context: Context) {

    enum class Status { OFF, SEARCHING, TRACKING }

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var detector: FaceDetector? = null
    private var executor: ExecutorService? = null
    private var armed = true
    private var lastTrigger = 0L

    fun start(
        owner: LifecycleOwner,
        mode: FaceGestureMode,
        sensitivity: Float,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
    ) {
        stop()
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(
                if (mode == FaceGestureMode.SMILE) FaceDetectorOptions.CLASSIFICATION_MODE_ALL
                else FaceDetectorOptions.CLASSIFICATION_MODE_NONE
            )
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
                    _status.value = Status.TRACKING
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
                if (abs(yaw) < threshold * 0.4f) armed = true
                if (armed && !coolingDown) {
                    if (yaw < -threshold) fire(now, onNext) else if (yaw > threshold) fire(now, onPrevious)
                }
            }
            FaceGestureMode.SMILE -> {
                val p = face.smilingProbability ?: return
                if (p < 0.3f) armed = true
                if (armed && !coolingDown && p > 0.92f - 0.3f * s) fire(now, onNext)
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

    companion object {
        private const val COOLDOWN_MS = 900L
    }
}
