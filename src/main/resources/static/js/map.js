// 시군구 SVG 지도 + 지역 패널
// 흐름: 로딩 → 지도 렌더 → 호버/클릭/키보드/검색/무작위 → fetch(/api/regions) → 패널 슬라이드 인
(function () {
    'use strict';

    const VIEW_W = 800;
    const VIEW_H = 1000;
    const FULL_VIEWBOX = [0, 0, VIEW_W, VIEW_H];
    const SVG_NS = 'http://www.w3.org/2000/svg';
    const ZOOM_MS = 350;

    let svg, panel, panelBody;
    const boundsByCode = {};   // SIG_CD → [[x0,y0],[x1,y1]]
    let vbAnim = null;         // 진행 중인 viewBox 애니메이션 취소용

    /* ---------- 유틸 ---------- */
    function el(tag, cls, text) {
        const n = document.createElement(tag);
        if (cls) n.className = cls;
        if (text != null) n.textContent = text;
        return n;
    }
    function retrigger(node, cls) {
        node.classList.remove(cls);
        void node.offsetWidth;
        node.classList.add(cls);
    }
    function setShow(node, show) {
        node.style.display = show ? '' : 'none';
    }
    const easeInOut = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);

    /* ---------- 지도 렌더 ---------- */
    async function renderMap() {
        const loading = document.getElementById('map-loading');
        let topo;
        try {
            const res = await fetch('/geo/sig.json');
            if (!res.ok) throw new Error('HTTP ' + res.status);
            topo = await res.json();
        } catch (err) {
            console.error('[map] sig.json 로드 실패:', err);
            if (loading) loading.querySelector('span').textContent = '지도를 불러오지 못했어요.';
            return;
        }

        const objectName = Object.keys(topo.objects)[0];
        const fc = topojson.feature(topo, topo.objects[objectName]);
        const projection = d3.geoMercator().fitSize([VIEW_W, VIEW_H], fc);
        const path = d3.geoPath(projection);

        const frag = document.createDocumentFragment();
        let drawn = 0;
        for (const f of fc.features) {
            const d = path(f);
            if (!d) continue;
            const sigCd = f.properties.SIG_CD;
            const name = f.properties.SIG_KOR_NM;
            boundsByCode[sigCd] = path.bounds(f);

            const p = document.createElementNS(SVG_NS, 'path');
            p.setAttribute('d', d);
            p.setAttribute('class', 'sig-path');
            p.setAttribute('data-sig-cd', sigCd);
            p.setAttribute('data-name', name);
            // 접근성
            p.setAttribute('role', 'button');
            p.setAttribute('tabindex', '0');
            p.setAttribute('aria-label', name);
            const title = document.createElementNS(SVG_NS, 'title');
            title.textContent = name;
            p.appendChild(title);
            frag.appendChild(p);
            drawn++;
        }
        svg.appendChild(frag);
        console.log('[map] 시군구 path 렌더 완료:', drawn);
        if (loading) setShow(loading, false);

        // 마우스 클릭
        svg.addEventListener('click', (e) => {
            const t = e.target.closest ? e.target.closest('.sig-path') : null;
            if (t) selectRegion(t.getAttribute('data-sig-cd'));
        });
        // 키보드(엔터/스페이스)로 선택
        svg.addEventListener('keydown', (e) => {
            if (e.key !== 'Enter' && e.key !== ' ' && e.key !== 'Spacebar') return;
            const t = e.target.closest ? e.target.closest('.sig-path') : null;
            if (t) {
                e.preventDefault();
                selectRegion(t.getAttribute('data-sig-cd'));
            }
        });
    }

    /* ---------- viewBox 줌/팬 (부드럽게) ---------- */
    function animateViewBox(target) {
        const start = svg.getAttribute('viewBox').split(/\s+/).map(Number);
        if (vbAnim) cancelAnimationFrame(vbAnim);
        const t0 = performance.now();
        function step(now) {
            const p = Math.min(1, (now - t0) / ZOOM_MS);
            const k = easeInOut(p);
            const cur = start.map((s, i) => s + (target[i] - s) * k);
            svg.setAttribute('viewBox', cur.join(' '));
            if (p < 1) vbAnim = requestAnimationFrame(step);
        }
        vbAnim = requestAnimationFrame(step);
    }
    function zoomToRegion(sigCd) {
        const b = boundsByCode[sigCd];
        if (!b) return;
        const cx = (b[0][0] + b[1][0]) / 2;
        const cy = (b[0][1] + b[1][1]) / 2;
        const w = b[1][0] - b[0][0];
        const h = b[1][1] - b[0][1];
        // 지역을 살짝 여유있게 담되 과도한 확대는 제한
        let size = Math.max(w, h) * 2.4;
        size = Math.max(140, Math.min(size, VIEW_H));
        const boxH = size;
        const boxW = size * (VIEW_W / VIEW_H);
        animateViewBox([cx - boxW / 2, cy - boxH / 2, boxW, boxH]);
    }
    function resetZoom() {
        animateViewBox(FULL_VIEWBOX.slice());
    }

    /* ---------- 지역 선택 → 패널 ---------- */
    // 외부(추천 모달 등)에서 지역 선택 흐름을 재사용할 수 있게 노출
    window.selectRegion = selectRegion;

    async function selectRegion(sigCd) {
        if (!sigCd) return;
        svg.querySelectorAll('.sig-path.selected').forEach((p) => p.classList.remove('selected'));
        const target = svg.querySelector('.sig-path[data-sig-cd="' + sigCd + '"]');
        if (target) target.classList.add('selected');

        zoomToRegion(sigCd); // 해당 지역으로 살짝 이동/강조

        try {
            const res = await fetch('/api/regions/' + encodeURIComponent(sigCd));
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const data = await res.json();
            fillPanel(data, sigCd);
            openPanel();
            showSpotlight(false);   // 지역을 골랐으면 지도에 집중
        } catch (err) {
            console.error('[map] 지역 정보 로드 실패:', err);
        }
    }

    function fillPanel(data, sigCd) {
        document.getElementById('panel-name').textContent = data.name || '';
        document.getElementById('panel-province').textContent = data.province || '';
        document.getElementById('panel-ai').textContent = data.aiSummary || '';

        const spWrap = document.getElementById('panel-specialties-wrap');
        const sp = document.getElementById('panel-specialties');
        sp.innerHTML = '';
        const specialties = data.specialties || [];
        specialties.forEach((s) => sp.appendChild(el('span',
            'px-4 py-2 bg-surface-alt border border-border rounded font-body-main text-caption text-text-primary', s)));
        setShow(spWrap, specialties.length > 0);

        const shopWrap = document.getElementById('panel-shops-wrap');
        const shops = document.getElementById('panel-shops');
        shops.innerHTML = '';
        (data.shops || []).forEach((shop) => {
            const card = el('div', 'p-4 bg-surface border border-border rounded group hover:-translate-y-0.5 hover:border-primary-container transition-all duration-300');
            const top = el('div', 'flex justify-between items-start mb-2');
            top.appendChild(el('h4', 'font-section-title text-card-title text-text-primary', shop.name));
            top.appendChild(el('span', 'badge-sage', '착한가격업소'));
            const bottom = el('div', 'flex justify-between items-center mt-4');
            bottom.appendChild(el('span', 'font-body-main text-caption text-text-muted', shop.menu));
            bottom.appendChild(el('span', 'font-section-title text-body-main text-primary', shop.price));
            card.appendChild(top);
            card.appendChild(bottom);
            shops.appendChild(card);
        });
        setShow(shopWrap, (data.shops || []).length > 0);

        const courseWrap = document.getElementById('panel-course-wrap');
        const course = document.getElementById('panel-course');
        course.innerHTML = '';
        const points = data.briefCourse || [];
        if (points.length > 0) {
            course.appendChild(el('div', 'absolute left-[11px] top-2 bottom-6 w-[1px] border-l border-dotted border-outline-variant'));
            points.forEach((pt) => {
                const item = el('div', 'relative mb-8 last:mb-0 group');
                item.appendChild(el('div', 'absolute -left-6 top-0 w-6 h-6 rounded-full bg-surface border border-primary flex items-center justify-center z-10 font-section-title text-xs text-primary', String(pt.order)));
                const head = el('div', 'flex items-center gap-2 mb-1');
                head.appendChild(el('h4', 'font-section-title text-card-title text-text-primary', pt.name));
                head.appendChild(el('span', 'text-[11px] bg-surface-alt px-2 py-0.5 rounded border border-border text-text-muted', pt.type));
                item.appendChild(head);
                item.appendChild(el('p', 'font-body-main text-caption text-text-muted', pt.desc));
                course.appendChild(item);
            });
        }
        setShow(courseWrap, points.length > 0);

        document.getElementById('panel-go').setAttribute('href', '/region?sigCd=' + encodeURIComponent(sigCd));

        // '이 지역에서 하루 보내기' — 코스를 조립할 수 있는 지역에서만 내보낸다.
        // 눌러도 아무것도 안 나오는 버튼은 지역을 발견한 순간의 신뢰를 깎는다.
        const dayBtn = document.getElementById('panel-day');
        const dayNote = document.getElementById('panel-day-note');
        if (dayBtn) {
            const ok = data.dayPlanAvailable !== false;
            dayBtn.setAttribute('href', ok ? '/course?sigCd=' + encodeURIComponent(sigCd) + '&auto=true' : '#');
            // 안내 문구가 'hidden' 클래스로 숨겨져 있어 인라인 display 로는 못 되돌린다
            dayBtn.classList.toggle('hidden', !ok);
            if (dayNote) dayNote.classList.toggle('hidden', ok);
        }
    }

    function openPanel() {
        const wasOpen = panel.classList.contains('open');
        panel.classList.add('open');
        if (wasOpen) retrigger(panel, 'open'); // 이미 열려 있으면 진입 애니메이션 재생
        retrigger(panelBody, 'stagger-fade-in');
    }
    function closePanel() {
        panel.classList.remove('open');
        svg.querySelectorAll('.sig-path.selected').forEach((p) => p.classList.remove('selected'));
        resetZoom();
        showSpotlight(true);   // 선택 해제 → 다시 추천을 보여준다
    }

    /* ---------- 검색 자동완성 ---------- */
    function initSearch() {
        const box = document.getElementById('region-search');
        const input = document.getElementById('region-search-input');
        const list = document.getElementById('region-search-list');
        if (!box || !input || !list) return;
        const items = Array.from(list.querySelectorAll('.region-opt'));

        function filter() {
            const q = input.value.trim().toLowerCase();
            items.forEach((li) => setShow(li, (li.getAttribute('data-name') || '').toLowerCase().includes(q)));
        }
        input.addEventListener('focus', () => { list.classList.remove('hidden'); filter(); });
        input.addEventListener('input', () => { list.classList.remove('hidden'); filter(); });
        list.addEventListener('click', (e) => {
            const li = e.target.closest('.region-opt');
            if (!li) return;
            input.value = li.getAttribute('data-name') || '';
            list.classList.add('hidden');
            selectRegion(li.getAttribute('data-sig-cd'));
        });
        document.addEventListener('click', (e) => {
            if (!box.contains(e.target)) list.classList.add('hidden');
        });
    }

    /* ---------- 오늘의 숨은 여행지 ----------
       지역을 고르기 전 왼쪽 여백을 채운다. 지역을 고르면 지도에 집중하도록 숨긴다. */
    function spotlightEl() {
        return document.getElementById('map-spotlight');
    }

    function showSpotlight(show) {
        const el = spotlightEl();
        if (!el) return;
        el.style.opacity = show ? '' : '0';
        el.style.visibility = show ? '' : 'hidden';
    }

    const SPOTLIGHT_PAGE = 3;   // 한 번에 이어붙일 카드 수
    let spotlightLoading = false;
    let spotlightDone = false;

    /** 서버가 렌더한 카드와 같은 구조로 만든다(두 곳의 모양이 달라지지 않게 주의) */
    function spotlightCard(s) {
        const a = document.createElement('a');
        a.href = '/region?sigCd=' + s.sigCd;
        a.className = 'spotlight-card group bg-surface border-2 border-border rounded-xl overflow-hidden shadow-sm hover:border-primary hover:-translate-y-[2px] transition-all duration-300';
        a.setAttribute('data-sig-cd', s.sigCd);

        const esc = (v) => {
            const d = document.createElement('div');
            d.textContent = v == null ? '' : String(v);
            return d.innerHTML;
        };

        a.innerHTML =
            '<div class="relative h-[92px] bg-surface-alt overflow-hidden">' +
                '<img src="' + esc(s.image) + '" alt="' + esc(s.heroName) + '" loading="lazy" ' +
                     'class="w-full h-full object-cover group-hover:scale-[1.04] transition-transform duration-500"/>' +
                '<span class="absolute bottom-1.5 left-1.5 bg-surface/90 backdrop-blur-sm border border-border rounded-full px-2 py-0.5 font-caption text-caption text-text-muted">' +
                    esc(s.province) + '</span>' +
            '</div>' +
            '<div class="p-3 flex flex-col gap-1">' +
                '<div class="flex items-baseline gap-1.5 flex-wrap">' +
                    '<span class="font-card-title text-card-title text-text-primary">' + esc(s.name) + '</span>' +
                    '<span class="font-caption text-caption text-primary truncate">' + esc(s.heroName) + '</span>' +
                '</div>' +
                '<p class="font-caption text-caption text-text-muted leading-relaxed line-clamp-3">' + esc(s.description) + '</p>' +
                '<div class="flex items-center gap-2 pt-1.5 mt-0.5 border-t border-border font-caption text-caption text-text-muted">' +
                    '<span>관광지 <span class="text-text-primary font-semibold">' + esc(s.attractionCount) + '</span></span>' +
                    (s.shopCount > 0
                        ? '<span class="text-sage">· 착한가격 <span class="font-semibold">' + esc(s.shopCount) + '</span></span>'
                        : '') +
            '</div></div>';
        return a;
    }

    /** 이미 띄운 지역 — 같은 곳이 다시 나오지 않게 서버에 알려준다 */
    function loadedSigCds() {
        return Array.from(document.querySelectorAll('#spotlight-list .spotlight-card'))
            .map((c) => c.getAttribute('data-sig-cd'))
            .filter(Boolean);
    }

    async function loadMoreSpotlight() {
        if (spotlightLoading || spotlightDone) return;
        const list = document.getElementById('spotlight-list');
        const status = document.getElementById('spotlight-status');
        if (!list) return;

        spotlightLoading = true;
        try {
            const exclude = loadedSigCds();
            const qs = '?count=' + SPOTLIGHT_PAGE + (exclude.length ? '&exclude=' + exclude.join(',') : '');
            const res = await fetch('/api/spotlight' + qs);
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const items = await res.json();

            if (!items.length) {
                spotlightDone = true;
                if (status) status.textContent = '숨은 여행지를 모두 둘러봤어요';
                return;
            }
            items.forEach((s) => list.appendChild(spotlightCard(s)));
        } catch (e) {
            if (status) status.textContent = '불러오지 못했어요';
        } finally {
            spotlightLoading = false;
        }
    }

    function initSpotlight() {
        const scroller = document.getElementById('spotlight-scroll');
        const sentinel = document.getElementById('spotlight-sentinel');
        if (!scroller || !sentinel) return;

        // 감지점이 보이면 다음 페이지를 이어붙인다
        if ('IntersectionObserver' in window) {
            new IntersectionObserver((entries) => {
                if (entries.some((e) => e.isIntersecting)) loadMoreSpotlight();
            }, { root: scroller, rootMargin: '120px' }).observe(sentinel);
        } else {
            // 폴백: 스크롤이 끝에 가까워지면 로드
            scroller.addEventListener('scroll', () => {
                if (scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 120) {
                    loadMoreSpotlight();
                }
            });
        }
    }

    /* ---------- 무작위로 한 곳 보기 (룰렛) ----------
       여러 지역을 빠르게 훑다가 점점 느려지며 한 곳에 멈춘다.
       지금은 저평가 지수가 없어 단순 무작위. (지수 도입 시 가중 샘플링으로 교체) */

    const SPIN_STEPS = 26;      // 훑고 지나가는 지역 수
    const SPIN_MIN_MS = 45;     // 가장 빠를 때 간격
    const SPIN_MAX_MS = 430;    // 마지막 즈음 간격
    let spinning = false;

    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

    /** 끝으로 갈수록 느려지는 간격 (ease-out) */
    function spinDelay(step) {
        const t = step / SPIN_STEPS;
        return SPIN_MIN_MS + Math.pow(t, 3) * (SPIN_MAX_MS - SPIN_MIN_MS);
    }

    /**
     * 최종 착지 지역. 관광지가 적재된 지역 중에서 고른다
     * (아무 데나 고르면 패널이 빈 채로 열린다).
     * 실패하면 null → 호출부가 지도 위 아무 곳으로 대체한다.
     */
    async function randomTarget() {
        try {
            const res = await fetch('/api/recommend', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ styles: [], mood: '', freeText: '' }),
            });
            if (!res.ok) return null;
            const data = await res.json();
            return data && data.sigCd ? data.sigCd : null;
        } catch (e) {
            return null;
        }
    }

    async function pickRandom() {
        if (spinning) return;
        const paths = Array.from(svg.querySelectorAll('.sig-path'));
        if (!paths.length) return;

        spinning = true;
        const btn = document.getElementById('map-shuffle');
        if (btn) {
            btn.disabled = true;
            btn.classList.add('is-spinning');
        }

        // 목적지를 먼저 정해두고 연출을 돌린다(연출 중 네트워크 대기가 없도록)
        let finalSigCd = await randomTarget();
        if (!finalSigCd) {
            finalSigCd = paths[Math.floor(Math.random() * paths.length)].getAttribute('data-sig-cd');
        }

        // 접근성: 모션을 줄이도록 설정한 사용자에겐 바로 결과만 보여준다
        const reduceMotion = window.matchMedia
            && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

        if (!reduceMotion) {
            closePanel();
            svg.querySelectorAll('.sig-path.selected').forEach((p) => p.classList.remove('selected'));

            let prev = null;
            for (let i = 0; i < SPIN_STEPS; i++) {
                if (prev) prev.classList.remove('spinning');
                // 직전과 같은 곳이 연달아 걸리면 멈춘 것처럼 보인다
                let next = paths[Math.floor(Math.random() * paths.length)];
                if (next === prev && paths.length > 1) {
                    next = paths[(paths.indexOf(next) + 1) % paths.length];
                }
                next.classList.add('spinning');
                prev = next;
                await sleep(spinDelay(i));
            }
            if (prev) prev.classList.remove('spinning');
        }

        await selectRegion(finalSigCd);

        if (btn) {
            btn.disabled = false;
            btn.classList.remove('is-spinning');
        }
        spinning = false;
    }

    /* ---------- 초기화 ---------- */
    async function init() {
        svg = document.getElementById('korea-map');
        panel = document.getElementById('region-panel');
        panelBody = document.getElementById('panel-body');
        if (!svg || !panel) return;

        initSearch();
        initSpotlight();
        await renderMap();

        const closeBtn = document.getElementById('panel-close');
        if (closeBtn) closeBtn.addEventListener('click', closePanel);

        const shuffle = document.getElementById('map-shuffle');
        if (shuffle) shuffle.addEventListener('click', pickRandom);

        // 지도 진입 시 현재 위치 수집(부가 기능 · 거부해도 무시)
        if (typeof window.getCurrentPositionSafe === 'function') {
            window.getCurrentPositionSafe();
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
