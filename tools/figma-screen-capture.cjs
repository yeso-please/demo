// Capture local, signed-out UI evidence for the team Figma board.
const { chromium } = require(process.argv[2] || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const out = path.resolve('build/figma-screens');
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const results = [];
    for (const [name, route] of [
      ['01-entry','/'], ['02-preferences','/onboarding'], ['03-map','/map'],
      ['04-region','/region?sigCd=47170'], ['05-course','/course?sigCd=47170&courseId=1'],
      ['06-diary','/onboarding/visits'], ['07-nearby','/nearby'], ['08-discoveries','/my/discoveries'],
      ['09-login','/login'], ['10-signup','/signup'], ['11-my-trips','/my/trips']
    ]) {
      const response = await page.goto('http://localhost:8080'+route, {waitUntil:'networkidle', timeout:60000});
      await page.evaluate(() => document.fonts.ready);
      await page.screenshot({path:path.join(out,name+'.png')});
      results.push({name,route,url:page.url(),status:response.status(),text:(await page.locator('body').innerText()).slice(0,10000),controls:await page.locator('a,button,select,input').evaluateAll(ns=>ns.filter(n=>n.getBoundingClientRect().width>0).map(n=>({tag:n.tagName,text:(n.innerText||n.getAttribute('aria-label')||n.getAttribute('placeholder')||'').trim(),href:n.getAttribute('href'),id:n.id}))) });
    }
    async function state(name) {
      await page.evaluate(() => document.fonts.ready);
      await page.screenshot({path:path.join(out,name+'.png')});
      results.push({name,url:page.url(),text:(await page.locator('body').innerText()).slice(0,10000)});
    }
    await page.goto('http://localhost:8080/onboarding',{waitUntil:'networkidle'});
    await page.locator('#mbti-start').click();
    await state('12-question');
    for(let i=0;i<12;i++) {
      const q=page.locator('.mbti-q').nth(i);
      await q.locator('.mbti-a').first().click();
      await q.waitFor({state:'hidden'});
    }
    await page.locator('.tag-btn').first().click();
    await page.locator('.tag-btn').nth(1).click();
    await state('13-tags');
    await page.locator('#mbti-tags-next').click();
    await page.locator('#mbti-result').waitFor({state:'visible'});
    await state('14-result');
    await page.goto('http://localhost:8080/region?sigCd=47170',{waitUntil:'networkidle'});
    await page.locator('select[name="days"]').selectOption('2');
    await page.getByRole('button',{name:'이 지역으로 여행 떠나기'}).click();
    await page.waitForURL(/\/trip\/\d+/);
    await page.waitForLoadState('networkidle');
    await state('15-trip-day1');
    const tripUrl=page.url();
    await page.locator('.day-tab').nth(1).click();
    await state('16-trip-day2');
    await page.locator('.day-panel:not(.hidden) .food-find').click();
    await page.locator('#food-sheet-list').waitFor({state:'visible'});
    await page.waitForTimeout(1200);
    await state('17-food');
    await page.locator('#food-sheet-close').click();
    await page.locator('.day-tab').first().click();
    await page.locator('.day-panel:not(.hidden) .stay-register').scrollIntoViewIfNeeded();
    await state('18-stay-section');
    await page.goto('http://localhost:8080/my/trips',{waitUntil:'networkidle'});
    await state('19-my-trips-draft');
    fs.writeFileSync(path.join(out,'manifest.json'),JSON.stringify(results,null,2));
    console.log(JSON.stringify({tripUrl,screens:results.map(r=>({name:r.name,url:r.url})),states:results.slice(11)}));
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
