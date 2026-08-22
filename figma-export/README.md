# figma-export — Figma 반입용 자체 완결형 HTML

현재 화면 22종(14화면 + 상태 변형)을 **스타일이 포함된 단일 HTML 파일**로 뽑은 것입니다.
서버·인터넷 없이 파일 하나로 렌더링되며, Figma `html.to.design` 플러그인의
**"HTML 코드 붙여넣기"** 모드에 그대로 넣으면 됩니다.

> 함께 보기: [화면 명세서](../docs/SCREENS.md) · [데이터 제약](../docs/DATA.md) · [디자인 토큰](../docs/DESIGN-TOKENS.md)

## 왜 이 파일들이 필요한가

원본 템플릿은 Tailwind **Play CDN**을 써서, `bg-background` `text-text-primary` 같은
커스텀 토큰 클래스가 **브라우저 실행 시점에** CSS로 변환됩니다.
`.html` 원본을 그대로 복사하면 스타일이 하나도 없는 뼈대만 넘어갑니다.

이 파일들은 그 CSS를 Tailwind CLI로 미리 빌드해 `<style>`로 인라인했고,
Thymeleaf가 DB 데이터로 렌더링한 **최종 HTML**을 담고 있습니다.

## 파일 목록

### 비로그인
| 파일 | 화면 |
|---|---|
| `01-login.html` | 로그인 |
| `02-login-error.html` | 로그인 — 인증 실패 |
| `03-signup.html` | 회원가입 |
| `04-mbti-intro.html` | **여행 MBTI — 시작** |
| `05-mbti-question.html` | **여행 MBTI — 문항** |
| `06-mbti-result.html` | **여행 MBTI — 결과** |
| `07-map-logged-out.html` | 지도 — 비로그인 헤더 |
| `08-course-logged-out.html` | 코스 만들기 — "로그인하고 저장" |
| `09-review-feed.html` | 후기 둘러보기 (MBTI 뱃지) |

### 로그인 (demo@sumeun.kr)
| 파일 | 화면 |
|---|---|
| `10-map.html` | 지도 — 오늘의 숨은 여행지 카드 |
| `11-map-ai-modal.html` | 지도 — AI 추천 모달 열림 |
| `12-region-panel.html` | 지역 패널 (실데이터) |
| `13-region-detail.html` | 지역 상세 — 안동시 |
| `14-course-empty.html` | 코스 만들기 — 담기 전 |
| `15-course-with-stops.html` | 코스 만들기 — 추천 코스 담아온 상태 (목포) |
| `16-my-courses.html` | 내 코스 — 코스 있음 |
| `17-course-saved.html` | 저장 완료 — 동선 지도·거리·시간 |
| `18-review-detail.html` | 후기 상세 (MBTI 뱃지, 공유 대상) |
| `19-review-form.html` | 후기 작성 |
| `20-profile.html` | 프로필 (MBTI 뱃지) |
| `21-chat.html` | AI 여행 상담 |
| `22-my-courses-empty.html` | 내 코스 — **빈 상태** |

## 알려진 한계 (설계상 불가피)

정적 HTML이라 **JS가 그리는 부분은 비어 있습니다.** 리디자인 시 참고하세요.

| 화면 | 비어 있는 것 | 대안 |
|---|---|---|
| `10` `11` 지도 | 시군구 SVG 지도 | **`shoot-live.mjs`** 로 실제 앱에서 캡처 |
| `10` `11` 지도 | 우측 지역 패널 내용 | **`12-region-panel.html` 사용** (서버 렌더, 실데이터) |
| `14` `15` 코스 | 동선 지도, 담기로 추가되는 항목 | **`shoot-live.mjs`** (카카오 동선까지 나옴) |
| `17` 저장완료 | 동선 지도 | **`shoot-live.mjs`** |
| `21` 챗봇 | 대화 말풍선 | 첫 진입 화면(예시 질문)만. 말풍선은 실제 AI 호출이 필요해 여전히 불가 |
| `05` `06` MBTI | — | 문항·결과 상태를 클래스 조작으로 만들어 담았음 |

폰트는 외부 CDN 링크로 남아 있습니다(Pretendard, Gowun Batang, Material Symbols).
인터넷이 되면 정상 표시되고, 안 되면 시스템 폰트로 대체됩니다.

## Figma 반입 순서

1. Figma에서 `html.to.design` 플러그인 실행
2. **"HTML" / "Paste code"** 탭 선택
3. 파일을 텍스트 에디터로 열어 **전체 복사 → 붙여넣기 → Import**
4. 22개 반복
5. 반입 후: 최상위 프레임 이름을 파일명대로 정리, 지도 영역을 PNG로 교체

## Stitch 반입용 PNG (`_png/`)

Google Stitch 는 Figma 와 달리 **코드 임포트가 없습니다.** 입력은 텍스트 프롬프트와
**이미지**뿐이라, 화면을 옮기려면 PNG 로 찍어 올려야 합니다.

```bash
cd _build
npm i -D playwright && npx playwright install chromium   # 최초 1회

node shoot.mjs          # 위 HTML 22종 → _png/  (앱 꺼져 있어도 됨)
node shoot-live.mjs     # JS 화면 7종만 실제 앱에서 다시 찍어 덮어씀 (앱 필요)
```

둘 다 1440×900 뷰포트 · 2배 해상도 · 전체 높이로 찍습니다.

- `shoot.mjs` — `figma-export/*.html` 을 `file://` 로 연다. 번호로 일부만(`node shoot.mjs 10 11`),
  모바일 폭 한 벌 더(`--mobile`, 390px) 지원.
- `shoot-live.mjs` — 위 "알려진 한계" 표의 화면(`07` `10` `11` `14` `15` `17` `21`)을
  데모 계정으로 로그인해 실제 앱에서 찍는다. 지도 스켈레톤이 걷히길 기다렸다가 캡처하므로
  **시군구 SVG·카카오 동선이 모두 나온다.** 순서상 `shoot.mjs` → `shoot-live.mjs`.

`_png/` 는 커밋하지 않습니다(HTML 과 동일 취급).

PNG 와 함께 넣을 프롬프트는 [STITCH-PROMPT.md](STITCH-PROMPT.md) 에 정리해 두었습니다.
프로젝트 초기 프롬프트(디자인 토큰 전체) + 화면별 한 줄 설명 22개.

## 다시 생성하는 법

앱을 켠 상태(`localhost:8080`)에서:

```bash
cd _build
npm i -D tailwindcss@3 @tailwindcss/forms @tailwindcss/container-queries   # 최초 1회
npx tailwindcss -c tailwind.config.js -i input.css -o tw.css               # 템플릿 수정 시 필수
node export.mjs
```

- `tailwind.config.js` — `fragments/layout.html`의 인라인 config를 옮긴 것.
  **레이아웃의 토큰 설정을 바꾸면 이 파일도 같이 고쳐야 합니다.**
- `export.mjs` — 데모 계정으로 로그인해 화면을 수집. 업로드 이미지는 data URI로 인라인.

> ⚠ **`tw.css` 재빌드를 빠뜨리면** 새로 추가한 클래스가 빠져 화면이 깨진 채로 나옵니다.

데모 데이터(계정·코스·후기)가 없으면 로그인 화면들이 실패합니다.
`seed-demo.mjs`로 먼저 넣어주세요. 계정: `demo@sumeun.kr` / `sumeun1234`
