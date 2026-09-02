// 내 발견 지도 — 다녀온 지역이 하나씩 켜진다 (PRD 5.3 ③).
//
// /map 의 불빛이 '추천 후보'인 것과 달리, 여기서 켜진 불은 '다녀온 곳'이다.
// 두 화면이 같은 은유를 쓰되 의미가 겹치지 않도록 한 화면 안에서는 한 뜻만 쓴다.
(function () {
    'use strict';

    const SVG_NS = 'http://www.w3.org/2000/svg';
    const VIEW_W = 800, VIEW_H = 1000;

    const svg = document.getElementById('my-map');
    const base = document.getElementById('my-base');
    const glow = document.getElementById('my-glow');
    const loading = document.getElementById('map-loading');
    const listEl = document.getElementById('lit-list');
    const emptyEl = document.getElementById('empty');
    const litEl = document.getElementById('lit');
    const pctEl = document.getElementById('pct');
    const barEl = document.getElementById('bar');
    const nudgeEl = document.getElementById('nudge');

    const names = {};   // sigCd -> 지역명

    async function renderMap() {
        let topo;
        try {
            const res = await fetch('/geo/sig.json');
            topo = await res.json();
        } catch (e) {
            loading.textContent = '지도를 불러오지 못했어요.';
            return false;
        }
        const objectName = Object.keys(topo.objects)[0];
        const fc = topojson.feature(topo, topo.objects[objectName]);
        const projection = d3.geoMercator().fitSize([VIEW_W, VIEW_H], fc);
        const path = d3.geoPath(projection);

        const frag = document.createDocumentFragment();
        for (const f of fc.features) {
            const d = path(f);
            if (!d) continue;
            const cd = f.properties.SIG_CD;
            names[cd] = f.properties.SIG_KOR_NM || cd;

            const p = document.createElementNS(SVG_NS, 'path');
            p.setAttribute('d', d);
            p.setAttribute('class', 'region');
            p.setAttribute('data-sig-cd', cd);
            const title = document.createElementNS(SVG_NS, 'title');
            title.textContent = names[cd];
            p.appendChild(title);
            frag.appendChild(p);
        }
        base.appendChild(frag);
        loading.style.display = 'none';
        return true;
    }

    /** 켜진 지역에 빛을 얹는다 — 값은 tokens.css 의 --glow-* 에서 읽는다 */
    function paintGlow(sigCds) {
        glow.textContent = '';
        if (!sigCds.length) return;

        const css = getComputedStyle(document.documentElement);
        const color = (css.getPropertyValue('--glow-color') || '#FFC661').trim();
        const blur = (css.getPropertyValue('--glow-blur') || '7').trim();
        const opacity = (css.getPropertyValue('--glow-opacity') || '0.85').trim();

        document.getElementById('lit-glow-blur').setAttribute('stdDeviation', blur);
        glow.setAttribute('opacity', opacity);

        sigCds.forEach((cd) => {
            const src = base.querySelector('.region[data-sig-cd="' + cd + '"]');
            if (!src) return;
            const clone = document.createElementNS(SVG_NS, 'path');
            clone.setAttribute('d', src.getAttribute('d'));
            clone.setAttribute('fill', color);
            clone.setAttribute('stroke', 'none');
            glow.appendChild(clone);
        });
    }

    // 몇 편을 써야 한다고 말하지 않는다 — 한 편으로도 읽고,
    // 더 쓰면 결과가 덜 흔들린다는 사실만 알린다.
    function nudge(lit, total) {
        if (lit === 0) return '지도를 밝히려면 다녀온 곳부터 적어주세요.';
        if (lit === 1) return '한 편으로 읽고 있어요. 더 적을수록 덜 흔들립니다.';
        const dark = total - lit;
        return '아직 ' + dark.toLocaleString() + '곳이 어둡습니다.';
    }

    async function load() {
        let data;
        try {
            const res = await fetch('/api/my/discoveries');
            data = await res.json();
        } catch (e) {
            return;
        }
        const items = data.items || [];
        const total = data.total || 250;
        const lit = items.length;

        // 지도
        const codes = items.map((i) => i.sigCd);
        codes.forEach((cd) => {
            const p = base.querySelector('.region[data-sig-cd="' + cd + '"]');
            if (p) p.classList.add('lit');
        });
        paintGlow(codes);

        // 진행도
        litEl.textContent = lit;
        const pct = total ? (lit / total) * 100 : 0;
        pctEl.textContent = pct < 1 && pct > 0 ? '1% 미만' : Math.round(pct) + '%';
        // requestAnimationFrame 을 쓰면 안 된다 — 탭이 백그라운드일 때 실행되지 않아
        // 막대가 0 에 멈춘 채로 남는다. 값은 바로 넣고, 애니메이션은 CSS transition 에 맡긴다
        // (초기값이 인라인 style 의 0 이므로 첫 페인트 이후 변경이라 전환이 그대로 걸린다).
        barEl.style.width = Math.max(pct, lit ? 1.5 : 0) + '%';
        nudgeEl.textContent = nudge(lit, total);

        // 목록
        listEl.innerHTML = '';
        if (!lit) {
            emptyEl.classList.remove('hidden');
            return;
        }
        emptyEl.classList.add('hidden');
        items.forEach((it) => {
            const li = document.createElement('li');
            li.className = 'lit-row';

            const dot = document.createElement('span');
            dot.className = 'lit-dot';

            const nm = document.createElement('span');
            nm.className = 'lit-name';
            nm.textContent = names[it.sigCd] || it.sigCd;

            const tags = document.createElement('span');
            tags.className = 'lit-tags';
            tags.textContent = (it.tags || []).join('·');

            li.appendChild(dot);
            li.appendChild(nm);
            li.appendChild(tags);
            listEl.appendChild(li);
        });
    }

    renderMap().then((ok) => { if (ok) load(); });
})();
