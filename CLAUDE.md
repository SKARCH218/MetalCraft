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
| 함께 쓸 모드 | Sodium 0.9.x (26.2), Create Fly 6.0.9 (26.2용, 비공식 Fabric 포크) |
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
- Sodium 0.9는 렌더링을 Blaze3D 위로 옮겼다. 따라서 Metal 백엔드가 제대로 구현되면 Sodium은 별도 포팅 없이 동작해야 한다. 안 되는 부분만 따로 대응한다.

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

한 번에 하나씩 진행한다. 각 단계의 완료 조건을 `runClient`로 확인하고, 사용자에게 결과를 알린 뒤 다음 단계로 넘어간다.

| 단계 | 내용 | 완료 조건 |
|---|---|---|
| M0 | 빌드 기반 | Swift dylib가 Gradle로 빌드되어 jar에 들어가고, FFM으로 Swift 함수 하나를 호출해 로그 출력 |
| M1 | Metal 창 | 게임 창의 `CAMetalLayer`에 단색 클리어와 삼각형 하나 출력 |
| M2 | 바닐라 Metal 백엔드 | 월드, 엔티티, GUI, 하늘, 안개, 파티클이 OpenGL과 같은 모습으로 렌더링. F3에 "Metal" 표시. 같은 위치 스크린샷 비교 |
| M3 | Sodium 호환 | Sodium을 넣고 M2 조건 유지, FPS 측정 기록 |
| M4 | 쉐이더 파이프라인 엔진 | 테스트용 팩(그림자맵 + 블룸 + 톤매핑)이 전체 패스 구조로 동작. 게임 안에서 팩 끄기/켜기 가능 |
| M5 | MetalFX | 공간 → 시간 업스케일링 동작, 렌더 스케일 설정 반영. 모션 벡터 오류(잔상) 없음 |
| M6 | Create | Create Fly에서 폴백 동작 → Flywheel Metal 백엔드 → 테스트 팩에서 인스턴싱 gbuffers까지 동작. 대형 장치 앞에서 FPS 측정 |
| M7 | 쉐이더팩 포팅 | 아래 순서로 하나씩. 각 팩은 원본(Iris, 가능하면 Windows나 Linux)과 스크린샷 비교 |

### 쉐이더팩 포팅 순서
1. Complementary Shaders (첫 타깃, 구조 기준점)
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
./gradlew runClient         # 테스트 실행
cd native && swift build -c release   # 네이티브만 따로 빌드
```

- Metal 검증 레이어: `MTL_DEBUG_LAYER=1`, `MTL_SHADER_VALIDATION=1` 환경변수로 `runClient` 실행.
- GPU 프레임 캡처: `MTL_CAPTURE_ENABLED=1`로 실행 후 Xcode에서 캡처.
- 성능 측정은 같은 월드, 같은 좌표, 같은 시간대에서 OpenGL / Vulkan / Metal을 비교하고 결과를 `docs/benchmarks.md`에 기록한다.
- 이 프로젝트는 macOS에서만 빌드하고 테스트할 수 있다. Metal 관련 코드를 수정한 뒤에는 반드시 빌드와 `runClient`로 확인한다.

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
