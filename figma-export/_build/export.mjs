// 실행 중인 앱(localhost:8080)의 서버 렌더링 결과를 받아,
// Tailwind Play CDN 대신 미리 빌드한 CSS 를 인라인한 "자체 완결형 HTML" 로 저장한다.
// => Figma html.to.design 의 "HTML 코드 붙여넣기" 모드로 바로 가져갈 수 있다.
//
// 로그인이 필요한 화면이 있어 데모 계정으로 세션을 만든 뒤 수집한다.
// 데모 데이터는 seed-demo.mjs 로 미리 넣어둔다.
//
// 실행:  cd _build && node export.mjs   (앱이 8080 에 떠 있어야 한다)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const BASE = "http://localhost:8080";
const BUILD = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(BUILD, "../..");
const RES = path.join(ROOT, "src/main/resources");
const OUT = path.join(ROOT, "figma-export");
const DEMO = {
    email: process.env.SUMEUN_DEMO_EMAIL,
    password: process.env.SUMEUN_DEMO_PASSWORD,
};
if (!DEMO.email || !DEMO.password) {
    throw new Error("SUMEUN_DEMO_EMAIL과 SUMEUN_DEMO_PASSWORD 환경변수가 필요합니다.");
}

const TW = fs.readFileSync(path.join(BUILD, "tw.css"), "utf8");
const TOKENS = fs.readFileSync(path.join(RES, "static/css/tokens.css"), "utf8");
const ANIM = fs.readFileSync(path.join(RES, "static/css/animations.css"), "utf8");

/* ---------- 세션 ---------- */
let cookie = "";
async function req(p, init = {}) {
    const res = await fetch(BASE + p, {
        ...init,
        headers: { ...(init.headers || {}), ...(cookie ? { cookie } : {}) },
        redirect: "manual",
    });
    const set = res.headers.getSetCookie?.().find((c) => c.startsWith("JSESSIONID="));
    if (set) cookie = set.split(";")[0];
    return res;
}
const page = async (p) => (await req(p)).text();
const csrf = async (p) => ((await page(p)).match(/name="_csrf"\s+value="([^"]+)"/) || [])[1];

/* ---------- HTML 정리 ---------- */

/** 업로드 이미지는 localhost 경로라 Figma 쪽에서 못 불러온다 → data URI 로 심는다 */
async function inlineUploads(src) {
    const paths = [...new Set(
        [...src.matchAll(/\/uploads\/[A-Za-z0-9/_-]+\.(?:png|jpe?g|gif|webp)/g)].map((m) => m[0]))];
    let out = src;
    for (const p of paths) {
        try {
            const res = await fetch(BASE + p);
            if (!res.ok) continue;
            const buf = Buffer.from(await res.arrayBuffer());
            const ext = p.split(".").pop().toLowerCase();
            out = out.split(p).join(`data:image/${ext === "jpg" ? "jpeg" : ext};base64,${buf.toString("base64")}`);
        } catch { /* 실패하면 원래 경로를 둔다 */ }
    }
    return out;
}

/** CDN 스크립트·로컬 CSS 링크·앱 JS 를 제거하고, 빌드한 CSS 를 인라인 */
function inline(src) {
    return src
        .replace(/<script src="https:\/\/cdn\.tailwindcss\.com[^"]*"><\/script>/g, "")
        .replace(/<script>\s*tailwind\.config[\s\S]*?<\/script>/g, "")
        .replace(/<link[^>]*href="\/css\/[^"]*"[^>]*>/g, "")
        .replace(/<script[^>]*src="\/js\/[^"]*"[^>]*><\/script>/g, "")
        .replace(/<script[^>]*dapi\.kakao\.com[^>]*><\/script>/g, "")
        .replace(/<\/head>/, `<style>\n${TOKENS}\n${ANIM}\n${TW}\n</style>\n</head>`);
}

async function write(name, src) {
    fs.writeFileSync(path.join(OUT, name), inline(await inlineUploads(src)), "utf8");
    console.log("  ✓", name);
}

/** 특정 요소에서 hidden 클래스를 빼 "열린 상태"를 만든다 */
const unhide = (src, marker) => src.replace(marker + ' class="hidden ', marker + ' class="');

/* ============================================================ */

fs.mkdirSync(OUT, { recursive: true });
console.log("수집 시작 —", BASE);

/* ---------- 비로그인 ---------- */
console.log("\n[비로그인]");
await write("01-login.html", await page("/"));
await write("02-login-error.html", await page("/?error"));
await write("03-signup.html", await page("/signup"));

/* 온보딩 = 여행 MBTI 검사. 시작 → 문항 → 결과 세 상태를 각각 뽑는다.
   화면 전환이 JS 라 여기서 클래스를 직접 조작해 각 상태를 만든다. */
const onboarding = await page("/onboarding");
await write("04-mbti-intro.html", onboarding);

// 문항 화면: 시작 숨기고 퀴즈 표시
await write("05-mbti-question.html", onboarding
    .replace('id="mbti-intro" class="flex-1', 'id="mbti-intro" class="hidden flex-1')
    .replace('id="mbti-quiz" class="hidden flex-1', 'id="mbti-quiz" class="flex flex-1'));

// 결과 화면: 결과 섹션만 표시 (INFP 예시로 채운다)
await write("06-mbti-result.html", onboarding
    .replace('id="mbti-intro" class="flex-1', 'id="mbti-intro" class="hidden flex-1')
    .replace('id="mbti-result" class="hidden flex-1', 'id="mbti-result" class="flex flex-1')
    .replace('>🎈<', '>🌙<')
    .replace('>ENFP<', '>INFP<')
    .replace('>우연을 사랑하는 방랑자<', '>마음에 담는 몽상가<')
    .replace('>계획에 없던 발견을 반기는 타입<', '>여운을 오래 간직하는 타입<')
    .replace('>추천 성향<', '>고요한 자연, 오래된 골목, 혼자 걷기 좋은 길<'));

await write("07-map-logged-out.html", await page("/map"));
await write("08-course-logged-out.html", await page("/course?sigCd=47170"));
await write("09-review-feed.html", await page("/reviews"));

/* ---------- 로그인 ---------- */
const token = await csrf("/");
const login = await req("/login", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded; charset=UTF-8" },
    body: new URLSearchParams({ ...DEMO, _csrf: token }),
});
if (login.status !== 302 || !(login.headers.get("location") || "").includes("/map")) {
    console.error("\n데모 계정 로그인 실패 — seed-demo.mjs 를 먼저 실행하세요.");
    process.exit(1);
}
console.log("\n[로그인: " + DEMO.email + "]");

const map = await page("/map");
await write("10-map.html", map);
await write("11-map-ai-modal.html", unhide(map, 'id="recommend-modal"'));

await write("12-region-panel.html", await page("/region/panel"));
await write("13-region-detail.html", await page("/region?sigCd=47170"));
await write("14-course-empty.html", await page("/course?sigCd=47170"));
// 추천 코스를 담아온 상태 — 경유지가 서버에서 렌더된다
await write("15-course-with-stops.html", await page("/course?sigCd=46110&courseId=385"));

/* 내 코스 → 후기 → 코스 id 역추적 */
const myCourses = await page("/my/courses");
await write("16-my-courses.html", myCourses);

const reviewId = (myCourses.match(/href="\/review\/(\d+)"/) || [])[1];
let courseId = (myCourses.match(/href="\/review\/new\?courseId=(\d+)"/) || [])[1];
if (!courseId && reviewId) {
    const detail = await page(`/review/${reviewId}`);
    courseId = (detail.match(/\/review\/new\?courseId=(\d+)/) || [])[1];
}

if (courseId) {
    await write("17-course-saved.html", await page(`/course/saved?courseId=${courseId}`));
    await write("19-review-form.html", await page(`/review/new?courseId=${courseId}`));
} else {
    console.warn("  ! 코스 id 를 찾지 못해 16/18 을 건너뜁니다.");
}
if (reviewId) {
    await write("18-review-detail.html", await page(`/review/${reviewId}`));
}

await write("20-profile.html", await page("/profile"));
await write("21-chat.html", await page("/chat"));

/* ---------- 빈 상태 (새 계정) ---------- */
console.log("\n[빈 상태]");
cookie = "";
const t2 = await csrf("/signup");
await req("/signup", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded; charset=UTF-8" },
    body: new URLSearchParams({
        email: `empty${Date.now()}@example.invalid`, password: DEMO.password,
        confirmPassword: DEMO.password, nickname: "새 여행자", _csrf: t2 }),
});
await write("22-my-courses-empty.html", await page("/my/courses"));

console.log("\n→", OUT);
