package com.scorebook.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 개인정보처리방침 주소 (저장소의 docs/privacy.html 을 GitHub Pages 로 공개) */
const val PRIVACY_POLICY_URL = "https://raehoon.github.io/musicscore-app/privacy.html"

private data class License(val name: String, val owner: String, val license: String)

private const val APACHE_2 = "Apache License 2.0"

/** 앱에 포함된 라이브러리. gradle/libs.versions.toml 에 의존성을 추가하면 여기도 함께 갱신할 것 */
private val LICENSES = listOf(
    License("AndroidX · Jetpack Compose · Room · DataStore · CameraX · Ink · Navigation", "The Android Open Source Project", APACHE_2),
    License("Material Icons", "Google", APACHE_2),
    License("Kotlin · kotlinx.coroutines · kotlinx.serialization", "JetBrains", APACHE_2),
    License("Dagger Hilt", "Google", APACHE_2),
    License("Coil", "Coil Contributors", APACHE_2),
    License("PdfBox-Android (Apache PDFBox)", "Tom Roush · The Apache Software Foundation", APACHE_2),
    License("Bouncy Castle", "The Legion of the Bouncy Castle", "MIT License"),
    License("Reorderable", "Calvin Liang", APACHE_2),
    License("ML Kit (얼굴 인식 · 글자 인식)", "Google", "ML Kit Terms of Service"),
)

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("오픈소스 라이선스") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LICENSES.forEach { item ->
                    Text(item.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "${item.owner} · ${item.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Apache License 2.0 전문: https://www.apache.org/licenses/LICENSE-2.0\n" +
                        "MIT License 전문: https://opensource.org/license/mit\n" +
                        "ML Kit 약관: https://developers.google.com/ml-kit/terms",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
