// 남의 켜진 지도 — /u/{닉네임}
//
// 어두운 전국 위에 그 사람이 다녀온 곳만 켜진다. 켜진 곳을 누르면 그때 쓴 글로 간다.
// 불빛은 /my/discoveries 와 같은 문법이다 — 여기서 켜진 불도 '다녀온 곳'이지 추천이 아니다.
(function () {
    'use strict';

    const VIEW_W = 800, VIEW_H = 1000;
    const SVG_NS = 'http://www.w3.org/2000/svg';

    const base = document.getElementById('pub-base');
    const glow = document.getElementById('pub-glow');
    const loading = document.getElementById('map-loading');
    const lit = Array.isArray(window.PUBLIC_LIT) ? window.PUBLIC_LIT : [];

    // sigCd -> diaryId
    const diaryOf = {};
    lit.forEach((l) => { diaryOf[l.sigCd] = l.diaryId; });

    (async function render() {
        let topo;
        try {
            topo = await (await fetch('/geo/sig.json')).json();
        } catch (e) {
            if (loading) loading.querySelector('span').textContent = '지도를 불러오지 못했어요.';
            return;
        }

        const key = Object.keys(topo.objects)[0];
        const fc = topojson.feature(topo, topo.objects[key]);
        const projection = d3.geoMercator().fitSize([VIEW_W, VIEW_H], fc);
        const path = d3.geoPath(projection);

        const frag = document.createDocumentFragment();
        const glowFrag = document.createDocumentFragment();

        for (const f of fc.features) {
            const d = path(f);
            if (!d) continue;
            const cd = f.properties.SIG_CD;
            const name = f.properties.SIG_KOR_NM || cd;
            const on = Object.prototype.hasOwnProperty.call(diaryOf, cd);

            const p = document.createElementNS(SVG_NS, 'path');
            p.setAttribute('d', d);
            p.setAttribute('class', on ? 'sig-path lit' : 'sig-path');
            if (on) {
                p.setAttribute('data-sig-cd', cd);
                p.setAttribute('tabindex', '0');
                p.setAttribute('role', 'link');
                p.setAttribute('aria-label', name + ' — 이때 쓴 글 보기');
                const t = document.createElementNS(SVG_NS, 'title');
                t.textContent = name;
                p.appendChild(t);

                // 빛은 채움만 남긴 복제본으로 그린다(선까지 번지면 지저분해진다)
                const c = document.createElementNS(SVG_NS, 'path');
                c.setAttribute('d', d);
                c.setAttribute('stroke', 'none');
                glowFrag.appendChild(c);
            }
            frag.appendChild(p);
        }

        base.appendChild(frag);
        glow.appendChild(glowFrag);
        if (loading) loading.style.display = 'none';
    })();

    function open(target) {
        const cd = target && target.getAttribute('data-sig-cd');
        const id = cd && diaryOf[cd];
        if (id) window.location.href = '/diary/' + id;
    }

    base.addEventListener('click', (e) => {
        const t = e.target.closest && e.target.closest('.sig-path.lit');
        if (t) open(t);
    });
    base.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        const t = e.target.closest && e.target.closest('.sig-path.lit');
        if (t) { e.preventDefault(); open(t); }
    });
})();
