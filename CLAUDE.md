# MetalCraft

마인크래프트 Java Edition의 렌더링을 Apple Metal로 교체하는 macOS 전용 Fabric 클라이언트 모드.
최종 목표: **쉐이더팩을 켠 상태에서 Create 모드를 렉 없이** 돌리는 것. MetalFX로 성능을 더 끌어올린다.

사용자와는 한국어로 대화한다. 코드, 주석, 커밋 메시지는 영어로 쓴다.

---

## 1. 고정된 환경 (임의로 바꾸지 말 것)

| 항목 | 값 |
|---|---|
| Minecraft | 26.2 |
| Mod loader | Fabric Loader 0.20.1, Loom 1.19-SNAPSHOT |
| Fabric API | 0.161.0+26.2 |
| 언어 | Kotlin (fabric-language-kotlin 1.14.2+kotlin.2.4.21), Mixin만 Java |
| JDK | Temurin 25 (JDK 26 사용 금지) |
| Mod ID / 패키지 | `metalcraft` / `dev.skarch.metalcraft` |
| 환경 | Client 전용, Split Sources 사용 |
| 함께 쓸 모드 | Create Fly 6.0.9 (26.2용, 비공식 Fabric 포크). Sodium은 지원하지 않는다(3-1 참고) |
| 플랫폼 | macOS, Apple Silicon(M1 이상)만. 개발 기기: MacBook Pro M1 Max 32GB |
| 최소 macOS | 14. MetalFX 프레임 보간은 macOS 26 이상에서만 켠다 |

버전을 올려야 할 이유가 생기면 먼저 사용자에게 묻는다.

## 2. 언어와 폴더 규칙

- **Mixin은 반드시 Java**로 `src/client/java/dev/skarch/metalcraft/mixin/`에 둔다. Mixin은 얇게 유지하고, 실제 로직은 Kotlin 쪽을 호출하기만 한다.
- **나머지는 전부 Kotlin**으로 `src/client/kotlin/dev/skarch/metalcraft/`에 둔다.
- **네이티브 Metal 코드는 Swift**로 `native/`(Swift Package)에 둔다.
- MSL 쉐이더는 `src/client/resources/assets/metalcraft/shaders/`에 둔다.

```
MetalCraft/
├─ CLAUDE.md
├─ native/                      # Swift Package → libMetalCraftNative.dylib
│  ├─ Package.swift
│  └─ Sources/MetalCraftNative/
├─ src/client/java/.../mixin/   # Java Mixin
├─ src/client/kotlin/.../
│  ├─ MetalCraftClient.kt       # 진입점
│  ├─ native/                   # FFM 바인딩 (Swift 함수 호출)
│  ├─ backend/                  # Blaze3D Metal 백엔드
│  ├─ pipeline/                 # 쉐이더 패스 엔진 (shadow/gbuffers/deferred/composite/final)
│  ├─ upscale/                  # MetalFX
│  ├─ flywheel/                 # Flywheel Metal 백엔드 (Create)
│  └─ config/                   # 설정, 게임 내 UI
├─ src/client/resources/
└─ shaderpacks/                 # 포팅한 쉐이더팩 (git 제외, 2-5 참고)
```

## 3. 아키텍처

### 3-1. Metal 백엔드 (핵심 방식)
- 개별 OpenGL 호출을 가로채지 않는다. **Blaze3D의 GPU 추상화 계층(`GpuDevice` 계열)을 Metal로 구현한 백엔드를 하나 추가**한다.
- 26.2에는 Mojang의 Vulkan 백엔드가 들어 있다. **이것을 기준 구현으로 삼는다.** Metal 백엔드는 Vulkan 백엔드와 같은 자리에 같은 방식으로 끼운다.
- 26.2의 실제 클래스와 메서드 이름은 추측하지 말고 `./gradlew genSources`로 디컴파일한 소스를 읽고 확인한다.
- 그래픽 옵션의 Graphics API 선택지에 "Metal"을 추가한다. Metal 초기화에 실패하면 로그를 남기고 Mojang 기본 렌더러로 돌아간다. 게임이 크래시되면 안 된다.
- **Sodium은 지원하지 않는다.** Sodium 0.9.2는 청크를 Blaze3D가 아니라 자체 OpenGL/Vulkan 호출로 그리므로 Metal 백엔드 위에서 동작하지 않는다(M3에서 확인). Sodium이 설치돼 있으면 MetalCraft는 Metal을 끄고 게임 기본 렌더러로 실행한다. 대신 MetalCraft가 자체 Metal 렌더링과 쉐이더 엔진을 갖는다. Sodium의 기법(청크 묶음 그리기 등)은 아이디어만 참고하고 코드는 가져오지 않는다.

### 3-2. 네이티브 브리지
- JNI는 쓰지 않는다. **Java FFM(Panama, `java.lang.foreign`)**으로 Swift 라이브러리를 호출한다.
- Swift 쪽은 `@_cdecl`로 C ABI 함수를 내보낸다. Metal 객체는 불투명 포인터(handle)로만 주고받는다.
- 객체 수명은 Swift 쪽이 관리하고, Kotlin 쪽은 명시적인 `destroy` 호출로 해제한다. 핸들 해제 누락이 없도록 소유권을 한 곳에 모은다.
- Gradle 태스크에서 `swift build -c release`를 실행하고, 결과 dylib를 jar 리소스에 넣는다. 런타임에 임시 폴더로 풀어서 로드한다.

### 3-3. 창
- GLFW 창은 그대로 쓴다. 26.2에서 Vulkan용으로 OpenGL 컨텍스트 없이 창을 만드는 경로가 있으니 그것을 활용한다.
- LWJGL의 `GLFWNativeCocoa.glfwGetCocoaWindow`로 NSWindow를 얻고, contentView에 `CAMetalLayer`를 붙인다.
- 창 크기, 레티나 배율 변경, 전체화면 전환 시 drawable 크기를 다시 맞춘다.

### 3-4. 쉐이더 변환
- 바닐라 코어 쉐이더와 리소스팩 쉐이더는 GLSL이다. **GLSL → SPIR-V(glslang) → MSL(SPIRV-Cross)**로 변환한다.
- Vulkan 백엔드가 이미 SPIR-V를 만들고 있다면 그 결과를 재사용한다. 디컴파일 소스로 확인한다.
- 바닐라 쉐이더는 빌드할 때 미리 변환한다. 리소스팩 쉐이더는 런타임에 변환하고 결과를 캐시한다.

### 3-5. 쉐이더 파이프라인 엔진
- Iris/OptiFine의 패스 구조를 따른다: `shadow → gbuffers_* → deferred → composite* → final`.
- colortex0~15, 깊이 버퍼(depthtex0~2), 그림자맵(shadowtex0/1, shadowcolor0/1)과 버퍼 플리핑을 지원한다.
- uniform 시스템: 카메라 행렬(이전 프레임 포함), 태양·달 위치, 월드 시간, 날씨, 플레이어 상태, 화면 크기, frameTimeCounter 등. 쉐이더팩을 포팅하면서 필요한 것을 추가한다.
- **범용 Iris 호환 계층은 만들지 않는다.** 쉐이더팩은 MSL로 직접 포팅한다.
- 쉐이더팩 형식: `shaderpacks/<이름>/pack.json`(패스 목록, 버퍼 포맷, 옵션) + `.metal` 파일들. 게임 안에서 팩을 바꿔 낄 수 있어야 한다.
- 모든 gbuffers 패스는 **일반 버전과 인스턴싱 버전**을 함께 만든다(3-7 참고).

### 3-6. MetalFX
- 1단계: 공간(Spatial) 업스케일링.
- 2단계: 시간(Temporal) 업스케일링. 필요한 것은 깊이, 모션 벡터, 카메라 지터.
  - 지형은 정지해 있으므로 이전·현재 카메라 행렬로 모션 벡터를 계산한다.
  - 엔티티와 Flywheel 인스턴스는 이전 프레임 변환 행렬로 모션 벡터를 따로 쓴다.
- GUI와 손(hand)은 업스케일 이후에 원래 해상도로 그린다.
- 3단계: 프레임 보간(macOS 26 이상). 입력 지연이 늘어나므로 기본값은 꺼둔다.
- 설정: 렌더 스케일(50~100%), 업스케일 방식(끔/공간/시간), 프레임 보간 on/off.

### 3-7. Create 지원 (Flywheel)
- Create는 Flywheel로 톱니, 축, 움직이는 장치를 GPU 인스턴싱으로 그린다. Flywheel은 OpenGL을 직접 쓰므로 그대로는 동작하지 않는다.
- 1단계: Flywheel을 끈 폴백 상태에서 Create가 Metal 백엔드로 정상적으로 보이는지 확인한다.
- 2단계: **Flywheel용 Metal 백엔드**를 구현한다. Flywheel의 백엔드 교체 구조를 따른다.
- 3단계: Flywheel 인스턴스를 쉐이더 파이프라인의 gbuffers 인스턴싱 패스로 그린다. 이것이 이 모드의 핵심 차별점이다(PC의 Iris & Oculus Flywheel Compat이 하는 일을 Metal에서 직접 한다).

## 4. 마일스톤

한 번에 하나씩 진행한다. **순서 변경(2026-11-15, 사용자 결정): M5 다음에 M7의 첫 팩인 Complementary 포팅을 먼저 하고, 그 뒤 M6, 이어서 M7의 나머지 팩 순으로 진행한다.** M0.5 이후로는 각 단계의 완료 조건을 **자동 테스트(6-1)로 직접 확인**하고, 결과 스크린샷과 수치를 사용자에게 알린 뒤 다음 단계로 넘어간다.

| 단계 | 내용 | 완료 조건 |
|---|---|---|
| M0 | 빌드 기반 | Swift dylib가 Gradle로 빌드되어 jar에 들어가고, FFM으로 Swift 함수 하나를 호출해 로그 출력 |
| M0.5 | 자동 테스트 하네스 | 6-1의 명령 하나로 마크 실행 → 테스트 장면 스크린샷 → 지표 기록 → 자동 종료까지 동작. 현재 기본 렌더러(OpenGL)로 기준 스크린샷 세트 생성 |
| M1 | Metal 창 | 게임 창의 `CAMetalLayer`에 단색 클리어와 삼각형 하나 출력 |
| M2 | 바닐라 Metal 백엔드 | 월드, 엔티티, GUI, 하늘, 안개, 파티클이 OpenGL과 같은 모습으로 렌더링. F3에 "Metal" 표시. 같은 위치 스크린샷 비교 |
| M3 | Sodium 호환 | **중단.** Sodium 0.9.2는 Metal 위에서 동작할 수 없음을 확인. Sodium이 있으면 Metal을 끄도록 처리. 쉐이더는 자체 엔진(M4)으로, 지원 팩은 아래 목록만 |
| M4 | 쉐이더 파이프라인 엔진 | 테스트용 팩(그림자맵 + 블룸 + 톤매핑)이 전체 패스 구조로 동작. 게임 안에서 팩 끄기/켜기 가능 |
| M5 | MetalFX | 공간 → 시간 업스케일링 동작, 렌더 스케일 설정 반영. 모션 벡터 오류(잔상) 없음 |
| M6 | Create | Create Fly에서 폴백 동작 → Flywheel Metal 백엔드 → 테스트 팩에서 인스턴싱 gbuffers까지 동작. 대형 장치 앞에서 FPS 측정 |
| M7 | 쉐이더팩 포팅 | 아래 순서로 하나씩. 각 팩은 원본(Iris, 가능하면 Windows나 Linux)과 스크린샷 비교 |

### 쉐이더팩 포팅 순서
1. Complementary Shaders (첫 타깃, 구조 기준점). Reimagined r5.9.3 원본은 `shaderpacks/_original/`에 둔다
2. Rethinking Voxels (Complementary 기반, 맥 OpenGL 4.1에서 못 쓰는 기능 사용)
3. AstraLex
4. Photon Shaders
5. Bliss Shaders
6. Solas Shader
7. Kappa Shader
8. Sildur's Vibrant Shaders

포팅 주의점:
- Metal에는 지오메트리 쉐이더가 없다. 쓰는 팩은 컴퓨트 쉐이더나 메시 쉐이더로 대체한다.
- 팩의 옵션 시스템(`shaders.properties`의 프로필, 슬라이더)은 `pack.json` 옵션으로 옮긴다.
- 원본 팩 코드에 드라이버 의존 동작이 있을 수 있다. 화면이 깨지면 원본과 패스별로 비교하며 찾는다.

## 5. 코딩 규칙

- **렌더 루프(매 프레임 실행 코드)에서 객체를 할당하지 않는다.** 람다 캡처, 박싱, 컬렉션 생성을 피하고 `inline` 함수와 원시 배열(`FloatArray`, `IntArray`)을 쓴다. 버퍼와 행렬은 재사용한다.
- Metal 커맨드 인코딩은 렌더 스레드에서만 한다. 쉐이더 변환과 파이프라인 상태 생성은 백그라운드에서 해도 된다.
- 프레임 동기화: 동시에 진행 중인 프레임은 최대 3개로 하고, 세마포어로 GPU가 쓰는 버퍼를 덮어쓰지 않게 한다.
- 로그는 `MetalCraft` 로거를 쓰고, 매 프레임 로그는 남기지 않는다.
- Swift 쪽 오류는 반환 코드와 오류 메시지 문자열로 Kotlin에 전달한다. 네이티브 크래시를 내지 않는다.
- 새 기능은 설정에서 끌 수 있게 만든다.

## 6. 빌드, 실행, 디버깅

```bash
./gradlew genSources        # 마인크래프트 디컴파일 소스 생성 (Blaze3D 구조 확인용)
./gradlew build             # 네이티브 + 모드 빌드
./gradlew runClient         # 직접 플레이용 실행
cd native && swift build -c release   # 네이티브만 따로 빌드

scripts/run-tests.sh                      # 자동 테스트: 전체 장면 촬영 + 빠른 지표 (OpenGL, 약 4분)
scripts/run-tests.sh --backend vulkan     # 렌더러만 바꿔 실행 (opengl | vulkan | metal), OpenGL 기준과 자동 비교
scripts/run-tests.sh --benchmark          # 벤치마크: 장면마다 10초 워밍업 + 30초 측정, docs/benchmarks.md에 표 추가
scripts/run-tests.sh --scenes cave,nether # 일부 장면만
scripts/run-tests.sh --save-baseline      # 이번 실행을 기준 세트(run/test-baseline/<backend>)로 저장
scripts/run-tests.sh --shaderpack test    # Metal + 쉐이더팩으로 실행 (결과와 기준 폴더는 metal-<팩 이름>)
scripts/run-tests.sh --upscale temporal --render-scale 67   # Metal + MetalFX (spatial | temporal, 결과 폴더 metal-temporal67)
scripts/run-tests.sh --sodium             # Sodium을 넣고 실행 (호환성 확인용, 결과 폴더는 <backend>-sodium)
scripts/run-tests.sh --iris <팩.zip>      # 원본 팩을 Iris + Sodium + OpenGL로 실행 (비교 기준, 결과 폴더 iris-<팩>)

# 쉐이더팩 GLSL → MetalCraft 팩 변환 (포팅 보조 도구, M7)
./gradlew convertShaderPack -Ppack.source=shaderpacks/_original/<팩>/shaders -Ppack.output=shaderpacks/<이름> -Ppack.port=src/tools/resources/ports/<이름>.json
```

자동 테스트가 도는 동안 게임 창을 닫거나 Cmd+Q를 누르면 실행이 실패로 끝난다.

### 6-1. 자동 테스트 하네스

Claude Code가 사람 없이 마크를 실행하고 결과를 검증하기 위한 장치. M0.5에서 만들고, 이후 모든 마일스톤의 검증에 쓴다.

**구성**
- Fabric API의 클라이언트 게임테스트 기능을 사용한다. 26.2에서의 정확한 모듈 이름과 Loom 실행 태스크는 Fabric 문서와 Fabric API 소스로 확인한다. 쓸 수 없으면 MetalCraft 안에 같은 역할의 테스트 모드를 직접 만든다(시스템 프로퍼티 `-Dmetalcraft.test=true`로 켬).
- 렌더러 선택은 `-Dmetalcraft.backend=opengl|vulkan|metal`로 고정할 수 있게 한다. 같은 테스트를 백엔드만 바꿔 돌리기 위해서다.

**재현성 고정**
- 창 크기 1920×1200, 렌더 스케일 100%, 렌더 거리 12, vsync 끔, FPS 제한 없음
- 고정 시드 월드(일반 월드 1개 + 슈퍼플랫 1개). 시간과 날씨는 장면마다 고정하고 진행을 멈춘다(`doDaylightCycle`, `doWeatherCycle` 끔)
- 장면마다 좌표와 시점(yaw, pitch)을 고정하고, 청크 로딩이 끝날 때까지 기다린 뒤 촬영한다

**테스트 장면** (필요하면 추가)
1. 낮 평원 (기본 지형, 하늘, 구름)
2. 물과 바다 (반투명, 수면)
3. 밤 + 횃불 (조명, 어두운 영역)
4. 동굴 (안개, 블록 조명)
5. 네더 (다른 차원, 안개 색)
6. 엔티티 다수 (몹 50마리 이상, 아이템 드롭, 파티클)
7. GUI (인벤토리, 채팅, F3 화면)
8. Create 장치 (M6부터: 톱니·축이 수백 개인 고정 구조물, 회전 중인 상태)

**출력**
```
run/test-output/<날짜-시간>/<backend>/
├─ <장면>.png
├─ metrics.json      # 장면별 평균 FPS, 1% low, 평균·p99 프레임 시간
└─ log.txt           # 경고, 에러, Metal 검증 레이어 메시지
```

**검증 방법**
- **이미지 비교**: 같은 장면의 OpenGL 기준 이미지와 Metal 이미지를 픽셀 단위로 비교하는 스크립트를 만든다. 차이 비율과 차이 부분을 강조한 diff 이미지를 출력한다. 쉐이더 정밀도 차이 같은 미세한 오차는 허용 기준을 두고 넘긴다.
- **눈으로 확인**: 차이가 기준을 넘거나 애매하면 Claude Code가 스크린샷과 diff 이미지를 직접 열어 보고 원인을 판단한다.
- **성능 측정**: 벤치마크 모드는 장면마다 10초 워밍업 후 30초 측정한다. 결과는 `docs/benchmarks.md`에 백엔드별 표로 누적 기록한다.
- 쉐이더팩 포팅(M7)에서는 원본 팩의 기준 이미지를 사용자가 따로 제공한다(맥에서 원본이 안 돌아가는 팩이 있기 때문). 기준 이미지가 없으면 사용자에게 요청한다.

**하네스로 확인할 수 없는 것**
움직일 때의 잔상, 깜빡임, 입력 지연, 조작감 같은 체감 요소는 자동 테스트로 판단하지 않는다. 마일스톤을 마칠 때 사용자가 직접 확인해야 할 항목을 짧은 체크리스트로 함께 알린다.

**구현 메모 (M0.5에서 정해진 것)**
- 테스트 코드는 `src/gametest/`(별도 모드 `metalcraft-gametest`, 배포 jar에 안 들어감)에 있고 Loom의 `runClientGameTest`로 실행된다. 진입점은 `scripts/run-tests.sh`.
- 일반 월드는 첫 실행에서 만든 것을 `run/test-worlds/`에 보관해 재사용한다. 같은 시드라도 월드 생성 결과가 실행마다 조금 달라지기 때문이다. `--fresh-worlds`로 지우면 기준 세트도 다시 저장해야 한다.
- 기준 세트는 `run/test-baseline/opengl/`(git 제외). 월드를 새로 만든 실행으로는 기준을 저장할 수 없다(한 번 더 실행).
- 촬영 순간에 텍스처 애니메이션·구름·조명 깜빡임을 고정하고 파티클을 지운다. 비네트는 꺼 둔다. 그래서 같은 렌더러끼리는 픽셀 단위로 일치한다(차이 0.01% 미만).
- 업스케일링을 켠 실행은 프레임 타이밍에 따라 결과가 조금씩 달라 픽셀 비교 기준을 두지 않는다. 회전 중에 찍는 `moving_camera.png`가 추가로 저장된다.
- 쉐이더팩을 켠 실행에는 `pack_off`, `pack_on_again` 장면이 추가된다(키로 팩을 껐다 켬). `pack_off`는 팩 없는 `after_reload`와, `pack_on_again`은 같은 실행의 `after_reload`와 같아야 한다.
- `after_reload` 장면은 창 크기 변경과 리소스 재로딩(F3+T와 같은 동작) 뒤의 화면을 찍는다.
- `entities_fx`(아이템 드롭, 연기)와 `gui_f3`는 본질적으로 매번 달라서 픽셀 비교에서 빼고 눈으로만 확인한다.
- 허용 기준: 채널 차이 8 초과인 픽셀이 0.5% 이하.
- M2 상태: Metal 백엔드는 `backend/Metal*.kt`(Blaze3D 인터페이스 구현)와 `native/.../Renderer.swift`(Metal 객체와 커맨드 인코딩)로 나뉜다. 셰이더는 게임에 들어 있는 shaderc와 SPIRV-Cross로 GLSL → SPIR-V → MSL 변환한다(런타임, 파이프라인 로딩 시점).
- 렌더러 선택: `config/metalcraft.properties`의 `renderer=metal|vanilla`(기본 metal). `-Dmetalcraft.backend=opengl|vulkan|metal`이 있으면 그것이 우선한다. Metal 시작에 실패하면 게임이 다음 백엔드로 넘어간다.
- 이미지 방향은 Vulkan 백엔드와 같다(0번 행이 화면 아래). 그래서 정점 셰이더에서 y를 뒤집고 화면 출력 때 다시 뒤집는다.
- vsync가 꺼져 있으면 drawable을 백그라운드 스레드가 받아 두고, 준비된 것이 없는 프레임은 그리기만 하고 화면에 내보내지 않는다(`DrawablePump`). 기다리면 FPS가 디스플레이 주사율에 묶인다.
- `-Dmetalcraft.debug.timing=true`: 렌더 스레드가 백엔드 안에서 기다린 시간을 500프레임마다 로그로 남긴다.
- M4 상태(쉐이더 엔진, `pipeline/ShaderPipeline.kt`): 팩 폴더는 `<게임 폴더>/shaderpacks/<이름>/`, 모드에 내장된 테스트 팩은 `src/client/resources/assets/metalcraft/shaderpacks/test/`. 팩 형식은 `pipeline/ShaderPack.kt` 주석, 바인딩 규칙과 공통 함수는 `assets/metalcraft/shaders/pack_header.metal`에 있다.
- 팩 선택: 게임 안에서 K 키(조작 설정에서 변경 가능)로 순환, `config/metalcraft.properties`의 `shaderpack=`에 저장. `-Dmetalcraft.shaderpack=<이름>`이 있으면 그것이 우선한다. 컴파일에 실패한 팩은 로그를 남기고 꺼진다.
- 프레임 흐름: `GameRendererMixin`이 월드 렌더링 앞뒤로 엔진을 부른다. 앞에서 그림자 패스를 돌리고, 월드를 그리는 동안 지형 파이프라인을 팩의 `gbuffers_terrain`으로 바꾸며 메인 이미지에 팩의 추가 버퍼를 함께 붙인다. 뒤에서 deferred → composite → final 전체화면 패스를 돌린다. 손과 GUI는 그 뒤에 게임이 그린다.
- M4에서 아직 없는 것: 엔티티 등 지형 이외의 팩 프로그램과 그림자, 카메라 시야 밖 물체의 그림자, "향상된 투명도" 설정과의 동시 사용(켜져 있으면 팩을 건너뜀), gbuffers 인스턴싱 버전(M6에서 Flywheel과 함께), deferred를 불투명 지오메트리 직후에 돌리는 것(지금은 월드 전체가 끝난 뒤).
- M5 상태(MetalFX, `upscale/Upscaler.kt`): `config/metalcraft.properties`의 `upscale=off|spatial|temporal`, `render_scale=50~100`(다음 실행부터 적용). `-Dmetalcraft.upscale`, `-Dmetalcraft.renderScale`이 있으면 그것이 우선한다.
- 업스케일링 중에는 월드를 작은 이미지에 그린다. 백엔드가 게임의 메인 색·깊이 이미지로 가는 패스와 클리어를 작은 이미지로 돌려 보내고(`Upscaler.redirect`), 월드와 쉐이더팩 패스가 끝나면 MetalFX가 메인 이미지로 키운다. 손과 GUI는 그 뒤에 원래 해상도로 그려진다.
- 시간 모드는 프로젝션에 서브픽셀 지터를 넣고, 깊이와 카메라 이동으로 모션 이미지를 만든다(`upscale_motion.fsh.metal`). 지터와 모션의 부호는 실험으로 정했다(원본 해상도와의 차이, 회전 중 프레임의 선명도).
- M5에서 아직 없는 것: 스스로 움직이는 것(엔티티, 이후 Create 장치)의 모션 벡터, 프레임 보간, 게임 내 설정 화면.
- M7 상태(Complementary 포팅 진행 중, 브랜치 `m7-complementary`): 팩의 GLSL은 변환기(`src/tools/.../ShaderPackConverter.kt`, 게임에 든 shaderc + SPIRV-Cross 사용, 모드 jar에는 안 들어감)로 MSL로 바꾼다. 팩 옵션은 기본값으로 고정한다. 변환 결과는 `shaderpacks/<이름>/`(git 제외)에 `pack.json`(`"format": 2`) + `.metal` + `blocks.properties`로 나오고, 중간 GLSL은 `_glsl/`에 남는다. 팩마다 손으로 정하는 설정은 `src/tools/resources/ports/<이름>.json`(버퍼 크기 비율, 이미지 텍스처, 블렌딩, 알파 테스트).
- 변환된 팩은 `pipeline/ConvertedPack.kt`가 돌린다. 팩의 OpenGL 규약(깊이 0=near, 느슨한 uniform, 그림자 행렬 방향)을 엔진이 맞춰 준다: uniform은 `PackUniforms` 블록 하나(이름과 오프셋은 `pack.json`), 그리기 종류별 값은 `DrawUniforms`(알파 테스트), 게임 깊이는 `1 - 깊이`로 복사해 depthtex0/1로 준다. 월드 프로그램은 게임 이미지 뒤에 붙인 팩 버퍼에만 쓰고, 팩 프로그램이 없는 게임 파이프라인은 그리지 않는다.
- 지형 정점은 Metal 사용 중 48바이트로 늘어난다(`pipeline/TerrainVertexFormat.kt`: 법선, 탄젠트, 블록 ID, 텍스처 중심). 블록 ID 표는 `pipeline/BlockIds.kt`.
- 개발 실행은 `-Dmetalcraft.shaderpacks.dir=<프로젝트>/shaderpacks`로 프로젝트의 `shaderpacks/`도 팩 폴더로 본다(`runClient`, 자동 테스트 모두).
- 변환기는 팩의 `world0`(오버월드 및 기타), `world-1`(네더), `world1`(엔드) 프로그램을 각각 변환하고, 엔진은 차원이 바뀌면 해당 세트로 다시 로딩한다. 지형(`gbuffers_terrain`, `gbuffers_water`), 엔티티·아이템(`gbuffers_entities`), 파티클(`gbuffers_textured`), 해·달(`gbuffers_skytextured`), 하늘(`gbuffers_skybasic`, 월드 전에 전체화면 패스로 실행), 그림자, deferred·composite·final이 팩 프로그램으로 돈다. 게임 파이프라인과 팩 프로그램의 대응은 `ConvertedPack.createSubstitute`에 있다.
- 성능(M1 Max, Complementary 기본 설정, 1920×1200, 2026-11-16): Metal 포팅은 평원 약 156, 바다 약 126, 물속 약 130, 네더 약 163 FPS. 같은 장면의 Iris + Sodium + OpenGL은 평원 약 103, 바다 약 90, 물속 약 95, 네더 약 132 FPS. 5120×2644에서는 Metal이 평원 약 41, 바다 약 33 FPS였다(물 그림자 등을 넣기 전 측정). 팩 프로그램은 Metal 컴파일 때 fast-math를 켠다(`PackProgram`의 `fastMath`). 끄면 3배쯤 느리다. 게임 자체 셰이더는 패스 사이 z-fighting 때문에 계속 끈다. 엔진의 CPU 시간은 프레임당 2ms 미만이다. 변환 시 SPIR-V 최적화는 효과가 없어 읽기 쉬운 비최적화 출력을 쓴다.
- 원본 팩 기준 실행: `scripts/run-tests.sh --iris <팩.zip>`이 Iris + Sodium을 넣고 OpenGL로 같은 장면을 찍는다(`shaderpacks/_original/`의 zip 사용, 결과 폴더 `iris-<팩>`, 픽셀 비교 없음). 포팅 결과와 화면·FPS를 비교하는 기준이다. Iris 버전은 `gradle.properties`의 `iris_version`.
- 변환된 팩이 켜져 있으면 지형 정점 색에서 게임이 곱해 둔 면 방향 음영을 빼고, 팩이 `separateAo`를 쓰면 AO를 알파로 옮긴다(`TerrainVertexFormat.colorMode`). 블록 아틀라스는 nearest 샘플러로 묶는다(게임은 선형 샘플러 + 자체 셰이더 필터링을 쓴다). 걷기 흔들림은 투영이 아니라 팩의 모델뷰 행렬에 넣는다. 그림자맵은 불투명만(shadowtex1)과 반투명 포함(shadowtex0) 두 장이다.
- 팩 프로그램이 없는 게임 파이프라인(블록 외곽선, 비, 번개, 이름표, 움직이는 블록 등)은 게임 셰이더가 팩의 색 버퍼에 직접 그린다(`MetalPipeline.variant`). 팩 조명은 받지 않는다. 바닐라 하늘·구름·엔티티 밑 그림자만 숨긴다.
- 측정용으로 `-Pmetalcraft.test.width=<w> -Pmetalcraft.test.height=<h>`(환경변수 `ORG_GRADLE_PROJECT_...`)로 자동 테스트의 화면 크기를 바꿀 수 있다. 기준 세트는 1920×1200이다.
- 자동 테스트의 팩 끄기/켜기는 K 키를 원하는 팩이 될 때까지 누른다(팩이 여러 개 설치돼 있어도 동작).
- M7에서 아직 없는 것: 엔티티 그림자, 손(게임이 팩 뒤에 자체 셰이더로 그림), 날씨·선·번개·신호기 빔·글린트·움직이는 블록용 팩 프로그램(지금은 게임 셰이더로 보이기만 함), 엔티티별 uniform(entityId, entityColor 등)과 손에 든 아이템 uniform(로그에 목록이 남는다), 팩 옵션.
- 아직 없는 것: 게임 내 그래픽 옵션의 "Metal" 선택지, GPU 타임스탬프 쿼리(F3의 GPU 사용률은 의미 없음), multi-draw·indirect draw(장치 기능으로 꺼 둠), 렌더 영역이 일부인 패스의 색·깊이 단독 클리어.
- Java 예외 한 곳: `backend/MetalBackendBase.java`. Mojang이 null을 넘기는데 non-null로 선언된 `GpuBackend.handleWindowCreationErrors`는 Kotlin으로 안전하게 오버라이드할 수 없어서 Java로 받는다.
- Metal 백엔드는 게임테스트 API가 하는 화면 출력 크기 보정(`GlCommandEncoderMixin`, `VulkanGpuSurfaceMixin` 참고)을 직접 처리해야 한다.

**실행 조건**
게임 창이 실제로 떠야 하므로 Mac이 로그인된 상태이고 화면이 켜져 있어야 한다. 실행에 실패하면 이 조건부터 확인한다.

### 6-2. 디버깅

- Metal 검증 레이어: `MTL_DEBUG_LAYER=1`, `MTL_SHADER_VALIDATION=1` 환경변수로 `runClient` 실행.
- GPU 프레임 캡처: `MTL_CAPTURE_ENABLED=1`로 실행 후 Xcode에서 캡처.
- 이 프로젝트는 macOS에서만 빌드하고 테스트할 수 있다. Metal 관련 코드를 수정한 뒤에는 반드시 빌드하고 자동 테스트를 돌려 확인한다.

## 7. 라이선스 주의

- **Sodium**(Polyform Shield): 소스 코드를 복사해 오지 않는다. 공개 API로만 연동한다.
- **Iris**(LGPL-3.0): 코드를 가져다 쓰면 MetalCraft도 LGPL 조건을 따라야 한다. 쓰기 전에 사용자에게 먼저 알린다.
- **Create Fly**(CC0): 자유롭게 참고해도 된다.
- **쉐이더팩**: 각 팩의 라이선스를 따른다. 포팅한 팩은 개인용이며 `shaderpacks/`는 `.gitignore`에 넣는다. 배포하려면 원작자 허락이 필요하다.
- 모드 자체 라이선스는 현재 All Rights Reserved. 바꿀 때는 사용자가 결정한다.

## 8. 범위 밖 (하지 말 것)

- Windows, Linux 지원, DirectX 백엔드, 별도 Vulkan 백엔드
- Intel Mac 지원
- 범용 Iris 쉐이더팩 호환 계층
- Create 애드온 포팅 (나중에 별도 작업으로 진행)

## 9. 참고 자료

- 26.2 디컴파일 소스의 Blaze3D와 Mojang Vulkan 백엔드
- Apple 문서: Metal, MetalFX, Metal Shading Language Specification
- glslang, SPIRV-Cross
- Iris 쉐이더팩 규격 문서 (패스, 버퍼, uniform 목록)
- Flywheel 소스 (백엔드 구조)
- 비슷한 모드: Pure Metal, MetalRender, MetalMine (구조 참고용, 코드 복사 금지)
