# PdfBox-Android: JPEG2000 디코더는 선택 의존성이라 경고만 무시
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# androidx.ink 는 JNI 로 네이티브 코드와 연결됨
-keep class androidx.ink.** { *; }

# kotlinx.serialization (내비게이션 라우트 / 백업 매니페스트)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit 은 리플렉션으로 ComponentRegistrar 의 기본 생성자를 찾는다 (R8 full mode 에서 제거되면 얼굴 인식 실패)
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.** { <init>(); }

# 배포용 난독화 강화: 모든 클래스를 하나의 패키지로 옮기고 이름을 짧게 바꾼다.
# 크래시 분석용 매핑 파일은 app/build/outputs/mapping/release/mapping.txt (버전마다 보관)
-repackageclasses 'h'
-allowaccessmodification
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
