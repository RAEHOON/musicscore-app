# Hoon's뷰어 (ScoreBook)

서버 없이 기기 안에서 모든 것을 처리하는 안드로이드 태블릿용 악보 앱.
Kotlin · Jetpack Compose · MVI.

## 빌드

- Android Studio (JDK 21+), Android SDK 37
- `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- `./gradlew :app:assembleRelease` → R8 축소·난독화된 ARM 전용 APK
- 사용 설명서: [docs/manual.html](docs/manual.html) (Chrome 에서 인쇄 → PDF 로 저장)

## 배포 (버전 관리)

1. `gradle.properties` 의 `appVersionCode` 를 1 올리고 `appVersionName` 을 바꾼다 (설정 화면 맨 아래에 표시됨)
2. `./gradlew :app:assembleRelease`
3. `app/build/outputs/mapping/release/mapping.txt` 를 버전별로 보관 (난독화된 크래시 로그 해석용)
4. `git tag v<버전>` 후 push

릴리스 서명은 루트의 `keystore.properties`(깃 제외)가 가리키는 키스토어를 쓴다. 파일이 없으면 디버그 키로 서명된다.
**키스토어를 잃어버리면 같은 앱으로 업데이트할 수 없으니 반드시 따로 백업할 것.**

```properties
storeFile=C:/dev/scorebook-keys/hoonsviewer-release.jks
storePassword=...
keyAlias=hoonsviewer
keyPassword=...
```

## 기능

| 영역 | 기능 |
|---|---|
| 라이브러리 | PDF 불러오기(여러 개, 중복 감지, 암호 PDF 안내), 다른 앱에서 "열기/공유", 썸네일 그리드, 검색(한글 초성), 정렬, 태그·즐겨찾기 필터, 정보 편집, 여러 악보 PDF 합치기 |
| 보기 | 한 페이지 / 가로 두 페이지 / 반 페이지 넘김 / 세로 스크롤, 핀치 확대(확대 영역만 고해상도 재렌더), 야간·세피아, 여백 자동 자르기, 마지막 페이지 기억 |
| 넘김 | 스와이프, 좌우 탭 영역, 블루투스 페달(PageUp/Down·방향키·스페이스), 볼륨키, 자동 넘김/자동 스크롤, 얼굴 제스처(고개 돌리기·미소·입 벌리기, ML Kit 온디바이스) |
| 필기 | 펜을 대면 자동으로 필기 모드, 펜·형광펜·지우개(획 단위 / 영역 + 크기 조절)·올가미(이동/삭제)·도형·텍스트·음악 기호 스탬프(크기 직접 입력, 지시어 160여 개), 레이어, 실행 취소/다시 실행, 손바닥 무시, S펜 버튼 = 지우개, 손가락 필기 토글 |
| 저장 | 필기는 획마다 즉시 DB 저장, "PDF로 저장"(새 파일 또는 원본 덮어쓰기, 벡터로 굽기) |
| 사진 악보 | 사진 여러 장 → 순서 변경·회전 → 악보 / PDF로 저장 |
| 페이지 편집 | 순서 변경, 회전, 자르기, 빈 오선지 삽입, 삭제, 선택 페이지로 새 악보 만들기(나누기) |
| 링크/북마크 | 도돌이표·D.S.·Coda 점프 링크(+돌아가기), 링크 목록에서 이름·이동 페이지 편집/삭제, 곡별 북마크 |
| 곡 자동 나누기 | 여러 곡이 붙은 악보집에서 끝세로줄(얇은 줄+굵은 줄)과 다음 페이지의 큰 제목을 찾아 곡 시작 페이지를 자동 감지, 제목은 온디바이스 OCR(+목차로 오타 보정), 검토 후 적용 → 상단 "곡" 바로가기로 곡 이동 |
| 셋리스트 | 순서 편집, 곡별 시작 북마크, 마지막 페이지에서 다음 곡 자동 열기 |
| 연습 도구 | 샘플 정확도 메트로놈(탭 템포), 음정 확인용 피아노(2옥타브, 옥타브 이동, 화음), 녹음, 음원 첨부 + 속도 조절 + A-B 구간 반복, 연습 시간 자동 기록/통계 |
| 백업 | DB + 악보 + 녹음 + 설정을 zip 하나로 백업/복원 |

## 구조

```
core/
  mvi/        MviViewModel<State, Intent, Effect> — 모든 화면의 단방향 데이터 흐름
  model/      도메인 모델
  database/   Room (가상 페이지, 획, 아이템, 레이어, 링크, 북마크, 셋리스트, 음원, 연습 기록)
  data/       Repository + BackupManager
  render/     PageRenderer(PdfRenderer 직렬화 + HARDWARE 비트맵 LRU), PageGeometry(월드↔화면 변환)
  ink/        Jetpack Ink 브러시, 획 직렬화, 지우개/올가미/도형 기하, 음악 기호 렌더러
  input/      InputHub — 액티비티 수준 펜/손가락 라우터
  analysis/   ScoreLayoutAnalyzer(오선·끝세로줄·제목 감지), SongSplitter(곡 나누기 + ML Kit 한글 OCR)
  pdf/        PdfExporter (PdfBox-Android)
  importer/   PDF/사진 가져오기
  audio/      Metronome, PianoSynth, Recorder, AudioPlayer
  face/       FaceGestureController (CameraX + ML Kit)
feature/      library, viewer, practicetools, pageeditor, imagescore, setlist, practice, settings
```

### 핵심 설계

- **가상 페이지**: 원본 PDF/이미지는 그대로 두고 순서·회전·자르기·빈 오선지를 메타데이터로 관리한다.
  필기는 페이지 ID 에 붙으므로 페이지를 옮겨도 따라간다. 실제 PDF 는 내보낼 때만 만든다.
- **월드 좌표**: 필기는 PDF 포인트 단위로 저장한다. 화면 크기, 확대, 회전과 무관하고, PDF 로 내보낼 때
  변환 행렬 하나로 정확히 같은 위치에 그려진다.
- **저지연 필기**: `MainActivity.dispatchTouchEvent` 에서 `InputHub` 가 펜 입력을 가로채
  Jetpack Ink `InProgressStrokesView`(프런트 버퍼 + 입력 예측)로 바로 보낸다. 손가락은 Compose 로 통과해
  페이지 넘김/확대에 쓰인다. 펜을 뗀 순간 완성된 획 하나만 MVI Intent 로 전달되므로 그리는 동안
  화면 재구성이 일어나지 않는다.
- **부드러운 넘김**: `PdfRenderer` 접근을 파일별 Mutex 로 직렬화하고, 결과를 HARDWARE 비트맵으로
  바꿔 LRU 캐시에 둔다. 다음/이전 페이지를 미리 렌더링한다.
