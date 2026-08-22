// figma-export/*.html 22종을 PNG 로 일괄 렌더한다.
// => Google Stitch 는 코드 임포트가 없고 이미지 입력만 받으므로, 이 PNG 를 업로드해 화면을 재생성시킨다.
//
// 자체 완결형 HTML 이라 앱(localhost:8080)이 꺼져 있어도 된다. file:// 로 직접 연다.
// 다만 폰트(Pretendard, Gowun Batang, Material Symbols)는 CDN 링크라 인터넷이 필요하다.
// 오프라인이면 시스템 폰트로 대체되어 렌더된다.
//
// 실행:  cd _build && npm i -D playwright && npx playwright install chromium
//        node shoot.mjs                 # 전체
//        node shoot.mjs 10 11 21        # 파일명 앞 번호로 일부만
//        node shoot.mjs --mobile        # 모바일 폭(390px)으로도 한 벌 더
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { chromium } from "playwright";

const SRC = path.resolve(fileURLToPath(import.meta.url), "../..");
const OUT = path.join(SRC, "_png");

// tokens.css 의 --max-w 가 1360px → 좌우 여백 포함해 1440 으로 잡는다.
const DESKTOP = { width: 1440, height: 900 };
const MOBILE = { width: 390, height: 844 };

const args = process.argv.slice(2);
const mobile = args.includes("--mobile");
const only = args.filter((a) => !a.startsWith("--"));

const files = fs
    .readdirSync(SRC)
    .filter((f) => f.endsWith(".html"))
    .filter((f) => only.length === 0 || only.some((n) => f.startsWith(n)))
    .sort();

if (files.length === 0) {
    console.error(only.length ? `일치하는 파일 없음: ${only.join(", ")}` : "HTML 파일이 없다.");
    process.exit(1);
}

fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();

/** 한 파일을 지정 뷰포트로 열어 전체 높이 스크린샷을 남긴다 */
async function shoot(file, viewport, suffix) {
    const page = await browser.newPage({ viewport, deviceScaleFactor: 2 });
    await page.goto(pathToFileURL(path.join(SRC, file)).href, { waitUntil: "load" });

    // 웹폰트가 내려오기 전에 찍으면 자간이 전부 틀어진다. 못 받아도 넘어간다.
    await page.evaluate(() => document.fonts.ready).catch(() => {});
    // 페이드인 계열 애니메이션(animations.css)이 끝나길 기다린다.
    await page.waitForTimeout(600);

    const out = path.join(OUT, file.replace(/\.html$/, `${suffix}.png`));
    await page.screenshot({ path: out, fullPage: true });
    await page.close();
    return out;
}

for (const file of files) {
    const d = await shoot(file, DESKTOP, "");
    console.log("✓", path.basename(d));
    if (mobile) {
        const m = await shoot(file, MOBILE, "@mobile");
        console.log("✓", path.basename(m));
    }
}

await browser.close();
console.log(`\n${files.length}개 → ${OUT}`);
