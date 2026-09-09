// Run: node tools/cannon-smoke.cjs <path-to-playwright> [base-url]
// Requires the local application to be running. No account or persisted data writes.
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { chromium } = require(process.argv[2] || 'playwright');
const base = process.argv[3] || 'http://localhost:8080';
const out = path.resolve('build/cannon-qa');
fs.mkdirSync(out, { recursive: true });

(async () => {
    const browser = await chromium.launch({ headless: true });
    try {
        const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
        const page = await context.newPage();
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.goto(`${base}/map`, { waitUntil: 'networkidle' });
        const dock = page.locator('#travel-cannon');
        await dock.locator('.travel-cannon__asset').waitFor();
        const count = page.locator('.travel-cannon__count');
        const status = page.locator('.travel-cannon__status');
        const launch = page.locator('.travel-cannon__asset');
        let draws = 0;
        page.on('request', req => { if (req.url().endsWith('/api/recommend')) draws++; });
        page.on('console', message => { if (message.type() === 'error' && message.text().includes('[cannon-fx]')) errors.push(message.text()); });
        assert.equal(await count.textContent(), '1명 탑승');
        assert.equal(await page.locator('.travel-cannon__launch').count(), 0);
        assert.equal(await dock.evaluate(node => getComputedStyle(node).backgroundColor), 'rgba(0, 0, 0, 0)');
        assert((await launch.boundingBox()).width >= 320);
        assert.equal(await page.locator('[data-crew-id="0"]').evaluate(node => getComputedStyle(node).backgroundColor), 'rgba(0, 0, 0, 0)');
        const mapCount = await page.locator('.sig-path').count();
        assert(mapCount > 200, `Expected national map, found ${mapCount} regions`);

        // Moving the cannon must not fire; its new muzzle is the flight's origin.
        const cannonBefore = await launch.boundingBox();
        await page.mouse.move(cannonBefore.x + cannonBefore.width / 2, cannonBefore.y + cannonBefore.height / 2);
        await page.mouse.down();
        await page.mouse.move(cannonBefore.x + cannonBefore.width / 2 - 170, cannonBefore.y + cannonBefore.height / 2 - 170, { steps: 20 });
        await page.mouse.up();
        const cannonAfter = await launch.boundingBox();
        assert(cannonAfter.x < cannonBefore.x - 150 && cannonAfter.y < cannonBefore.y - 150);
        assert.equal(draws, 0, 'Releasing a cannon drag must not launch');
        await page.screenshot({ path: path.join(out, 'desktop-cannon-moved.png') });

        await page.getByText('동행 아바타 추가', { exact: true }).click();
        await page.getByLabel('함께 갈 사람 이름').fill('친구');
        await page.getByRole('button', { name: '추가', exact: true }).click();
        const friend = page.locator('[data-crew-id="1"]');
        assert.equal(await friend.getAttribute('aria-pressed'), 'false');
        const from = await friend.boundingBox();
        const to = await page.locator('.travel-cannon__asset').boundingBox();
        await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
        await page.mouse.down();
        await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 16 });
        await page.screenshot({ path: path.join(out, 'desktop-drag.png') });
        await page.mouse.up();
        await page.waitForFunction(() => document.querySelector('.travel-cannon__count').textContent === '2명 탑승');

        // Real backend draw and real detail response, with the complete flight.
        await launch.click({ position: { x: 78, y: 78 } });
        await page.locator('#travel-cannon.is-lighting').waitFor();
        assert.equal(await page.locator('#travel-cannon.is-firing').count(), 0);
        await page.screenshot({ path: path.join(out, 'desktop-ignition.png') });
        await page.locator('#travel-cannon.is-firing').waitFor();
        await page.screenshot({ path: path.join(out, 'desktop-blast.png') });
        await page.locator('.travel-cannon-flight').waitFor({ state: 'attached' });
        await page.waitForFunction(() => [...document.querySelectorAll('.travel-cannon-flight > g')].filter(n => n.style.opacity === '1').length === 2);
        assert.equal(draws, 1);
        assert(await launch.isDisabled());
        await page.screenshot({ path: path.join(out, 'desktop-flight.png') });
        await page.locator('#travel-cannon.is-landing').waitFor();
        await page.screenshot({ path: path.join(out, 'desktop-landing.png') });
        await page.waitForFunction(() => document.querySelector('.travel-cannon__status').textContent.includes('도착!'));
        assert((await status.textContent()).includes('나, 친구'));
        assert.equal(await page.locator('.travel-cannon-flight').count(), 0);
        assert(!(await launch.isDisabled()));
        await page.screenshot({ path: path.join(out, 'desktop-arrival.png') });

        await page.locator('#panel-close').click();
        await page.getByRole('button', { name: '대포 원위치', exact: true }).click();
        await page.waitForFunction(x => Math.abs(document.querySelector('.travel-cannon__asset').getBoundingClientRect().x - x) < 3, cannonBefore.x);
        await launch.press('ArrowLeft');
        assert((await launch.boundingBox()).x < cannonBefore.x - 8);
        await launch.press('Home');
        await page.locator('[data-crew-id="0"]').click();
        await friend.click();
        await launch.click();
        assert((await status.textContent()).includes('한 명 이상'));
        assert.equal(draws, 1, 'Empty crew must not request a destination');
        await friend.press('Enter');
        assert.equal(await count.textContent(), '1명 탑승');

        // Server failure must keep passengers and unlock retry, never fake a draw.
        await page.route('**/api/recommend', route => route.fulfill({ status: 503, body: '{}' }));
        await launch.click();
        await page.waitForFunction(() => document.querySelector('.travel-cannon__status').classList.contains('is-error'));
        assert.equal(await count.textContent(), '1명 탑승');
        assert.equal(await page.locator('.travel-cannon-flight').count(), 0);
        assert(!(await launch.isDisabled()));
        await page.unroute('**/api/recommend');

        await launch.click();
        const skip = page.getByRole('button', { name: '애니메이션 건너뛰기' });
        await skip.waitFor({ state: 'visible' });
        await skip.click();
        await page.waitForFunction(() => document.querySelector('.travel-cannon__status').textContent.includes('도착!'));
        assert.equal(await page.locator('.travel-cannon-flight').count(), 0);

        // A small touch viewport, tap alternative, and reduced-motion destination.
        const mobileContext = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, reducedMotion: 'reduce' });
        const mobile = await mobileContext.newPage();
        mobile.on('pageerror', error => errors.push(error.message));
        await mobile.goto(`${base}/map`, { waitUntil: 'networkidle' });
        await mobile.locator('.travel-cannon__asset').waitFor();
        const mobileBox = await mobile.locator('#travel-cannon').boundingBox();
        assert(mobileBox.x >= 0 && mobileBox.x + mobileBox.width <= 390);
        assert(mobileBox.y >= 0 && mobileBox.y + mobileBox.height <= 844);
        // Real touch pointer sequence exercises touch-action and capture on the draggable asset.
        const mobileCannon = mobile.locator('.travel-cannon__asset');
        const touchBox = await mobileCannon.boundingBox();
        const cdp = await mobileContext.newCDPSession(mobile);
        const touchStart = { x: touchBox.x + touchBox.width / 2, y: touchBox.y + touchBox.height / 2 };
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [touchStart] });
        for (let i = 1; i <= 12; i++) await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: touchStart.x, y: touchStart.y - i * 9 }] });
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
        assert((await mobileCannon.boundingBox()).y < touchBox.y - 90);
        assert.equal(await mobile.locator('.travel-cannon-flight').count(), 0);
        await mobile.getByRole('button', { name: '대포 원위치', exact: true }).tap();
        await mobile.locator('[data-crew-id="0"]').tap();
        assert.equal(await mobile.locator('.travel-cannon__count').textContent(), '0명 탑승');
        await mobile.locator('[data-crew-id="0"]').tap();
        await mobile.screenshot({ path: path.join(out, 'mobile-ready.png') });
        await mobile.locator('.travel-cannon__asset').tap();
        await mobile.waitForFunction(() => document.querySelector('.travel-cannon__status').textContent.includes('도착!'));
        assert.equal(await mobile.locator('.travel-cannon-flight').count(), 0);
        await mobile.screenshot({ path: path.join(out, 'mobile-arrival.png') });
        assert.deepEqual(errors, [], 'Browser JavaScript errors');
        console.log(JSON.stringify({ result: 'PASS', regions: mapCount, checks: ['mouse cannon drag without launch', 'touch cannon drag', 'reset and keyboard movement', 'unboxed assets', 'fuse before explosion', 'avatar drag into moved cannon', 'group flight with real API', 'landing celebration', 'empty crew', 'keyboard boarding', 'API failure recovery', 'skip', 'mobile tap', 'reduced motion'], screenshots: out }));
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
