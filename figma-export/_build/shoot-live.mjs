// 실제로 돌아가는 앱(localhost:8080)을 브라우저로 열어 PNG 로 찍는다.
//
// shoot.mjs 는 figma-export/*.html(정적)을 찍기 때문에 JS 가 그리는 영역이 비어 있다.
// (지도 SVG, 카카오 동선, 챗 말풍선 → README "알려진 한계" 참고)
// 이 스크립트는 그 화면들만 실제 앱에서 찍어 같은 _png/ 폴더에 덮어쓴다.
//
// 실행:  앱을 띄운 뒤(./gradlew bootRun)
//        cd _build && node shoot-live.mjs
//        node shoot-live.mjs 10 11        # 번호로 일부만
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const BASE = "http://localhost:8080";
const DEMO = {
    email: process.env.SUMEUN_DEMO_EMAIL,
    password: process.env.SUMEUN_DEMO_PASSWORD,
};
if (!DEMO.email || !DEMO.password) {
    throw new Error("SUMEUN_DEMO_EMAIL과 SUMEUN_DEMO_PASSWORD 환경변수가 필요합니다.");
}
const OUT = path.join(path.resolve(fileURLToPath(import.meta.url), "../.."), "_png");
const VIEWPORT = { width: 1440, height: 900 };

const only = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const wanted = (name) => only.length === 0 || only.some((n) => name.startsWith(n));

fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 2 });
const page = await ctx.newPage();

/** 지도(#map-loading 스켈레톤)가 걷힐 때까지. 못 걷히면 그대로 찍고 경고를 남긴다. */
async function mapReady() {
    try {
        await page.waitForSelector("#map-loading", { state: "hidden", timeout: 15000 });
    } catch {
        console.warn("    ! 지도 스켈레톤이 안 걷혔다 — 네트워크/geo 데이터 확인");
    }
}

/** 카카오 동선 지도는 외부 SDK 라 확인할 선택자가 없다. 타일이 붙을 시간만 준다. */
const kakaoReady = () => page.waitForTimeout(3000);

async function shoot(name, url, after) {
    if (!wanted(name)) return;
    await page.goto(BASE + url, { waitUntil: "networkidle" });
    await page.evaluate(() => document.fonts.ready).catch(() => {});
    if (after) await after();
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: true });
    console.log("  ✓", name + ".png");
}

/* ---------- 비로그인 ---------- */
console.log("[비로그인]");
await shoot("07-map-logged-out", "/map", mapReady);

/* ---------- 로그인 ---------- */
await page.goto(BASE + "/", { waitUntil: "networkidle" });
await page.fill('input[name="email"]', DEMO.email);
await page.fill('input[name="password"]', DEMO.password);
await Promise.all([page.waitForURL("**/map**", { timeout: 15000 }), page.click('button[type="submit"]')]);
console.log(`\n[로그인: ${DEMO.email}]`);

await shoot("10-map", "/map", mapReady);

// AI 추천 모달 — 버튼 클릭 대신 hidden 을 직접 걷어 "열린 상태"를 만든다(export.mjs 와 동일한 방식)
await shoot("11-map-ai-modal", "/map", async () => {
    await mapReady();
    await page.evaluate(() => document.getElementById("recommend-modal")?.classList.remove("hidden"));
    await page.waitForTimeout(300);
});

// 지도에서 지역을 눌러 우측 패널이 열린 상태. 패널 내용(특히 AI 한 줄 요약)은 비동기라 채워질 때까지 기다린다.
// 패널은 자체 스크롤 영역(#panel-body)이라 기본 높이로는 아래가 잘린다 → 이 화면만 뷰포트를 높게 잡는다.
await page.setViewportSize({ ...VIEWPORT, height: 1600 });
await shoot("12b-map-region-panel", "/map", async () => {
    await mapReady();
    await page.click('.sig-path[data-sig-cd="47170"]'); // 경북 안동시 — 13번 상세와 같은 지역
    await page.waitForSelector("#region-panel.open", { timeout: 10000 });
    try {
        await page.waitForFunction(() => document.getElementById("panel-ai")?.textContent.trim().length > 0,
            null, { timeout: 20000 });
    } catch {
        console.warn("    ! AI 요약이 안 채워졌다 — 패널이 빈 채로 찍힌다");
    }
    await page.waitForTimeout(800); // 패널 진입 애니메이션
});
await page.setViewportSize(VIEWPORT);

await shoot("14-course-empty", "/course?sigCd=47170", kakaoReady);
await shoot("15-course-with-stops", "/course?sigCd=46110&courseId=385", kakaoReady);

/* 저장 완료 화면은 코스 id 가 필요하다 — 내 코스에서 역추적 (export.mjs 와 같은 방식) */
if (wanted("17-course-saved")) {
    await page.goto(BASE + "/my/courses", { waitUntil: "networkidle" });
    const html = await page.content();
    let courseId = (html.match(/href="\/review\/new\?courseId=(\d+)"/) || [])[1];
    if (!courseId) {
        const reviewId = (html.match(/href="\/review\/(\d+)"/) || [])[1];
        if (reviewId) {
            await page.goto(`${BASE}/review/${reviewId}`, { waitUntil: "networkidle" });
            courseId = ((await page.content()).match(/\/review\/new\?courseId=(\d+)/) || [])[1];
        }
    }
    if (courseId) await shoot("17-course-saved", `/course/saved?courseId=${courseId}`, kakaoReady);
    else console.warn("  ! 코스 id 를 찾지 못해 17 을 건너뛴다");
}

// 챗봇: 대화 말풍선은 실제 AI 호출이 있어야 생긴다. 여기선 첫 진입 화면만.
await shoot("21-chat", "/chat");

await browser.close();
console.log(`\n→ ${OUT}`);
