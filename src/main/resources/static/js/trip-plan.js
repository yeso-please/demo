// 상세 계획 화면 — Day 전환 · 항목 편집 · 저장 · 먹거리/숙소 등록 · 동선 지도.
//
// 이동시간과 순서는 서버가 다시 계산한다(TripPlanService). 화면에서 미리 고쳐 쓰지 않고,
// 저장에 성공하면 페이지를 다시 읽어 서버가 계산한 값을 그대로 보여준다 —
// 옛 추정값을 최신값처럼 보여주지 않기 위해서다(PRD F-07).
(function () {
    'use strict';

    const STAY_STEP = 10;      // 체류 시간 조절 단위(분)
    const STAY_MIN = 10;
    const STAY_MAX = 600;

    let root, tripId, version;
    let dirty = false;

    /* =========================================================
       공통
       ========================================================= */

    function $(sel, el) { return (el || document).querySelector(sel); }
    function $$(sel, el) { return Array.from((el || document).querySelectorAll(sel)); }

    function minutesText(m) {
        if (m <= 0) return '0분';
        const h = Math.floor(m / 60);
        const rest = m % 60;
        if (h > 0) return rest > 0 ? h + '시간 ' + rest + '분' : h + '시간';
        return rest + '분';
    }

    function setSaveState(kind, text) {
        const el = $('#save-state');
        if (!el) return;
        el.classList.remove('dirty', 'failed');
        if (kind) el.classList.add(kind);
        const label = $('.save-state-label', el);
        if (label) label.textContent = text;
    }

    function markDirty() {
        dirty = true;
        setSaveState('dirty', '저장하지 않은 변경');
    }

    /** 떠나기 전에 알린다 — 편집을 조용히 잃지 않게 */
    window.addEventListener('beforeunload', (e) => {
        if (!dirty) return;
        e.preventDefault();
        e.returnValue = '';
    });

    async function postJson(url, body) {
        const res = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        let data = {};
        try { data = await res.json(); } catch (e) { /* 본문이 없을 수 있다 */ }
        return { ok: res.ok, status: res.status, data: data };
    }

    /* =========================================================
       Day 전환
       ========================================================= */

    function activeDay() {
        const tab = $('.day-tab.active');
        return tab ? Number(tab.getAttribute('data-day')) : 1;
    }

    function showDay(day) {
        $$('.day-tab').forEach((t) => t.classList.toggle('active', Number(t.getAttribute('data-day')) === day));
        $$('.day-panel').forEach((p) => {
            const on = Number(p.getAttribute('data-day')) === day;
            p.classList.toggle('hidden', !on);
            p.classList.toggle('flex', on);
        });
        const label = $('#map-day-label');
        if (label) label.textContent = 'Day ' + day + ' 동선';
        renderMap();
    }

    /* =========================================================
       항목 편집
       ========================================================= */

    function panelOf(el) { return el.closest('.day-panel'); }

    /** 순번·빈 Day 안내·시간 합계를 다시 그린다 */
    function refreshPanel(panel) {
        if (!panel) return;
        const wraps = $$('.trip-item-wrap', panel);

        let sight = 0, brk = 0, sightCount = 0;
        wraps.forEach((w, i) => {
            const kind = w.getAttribute('data-kind');
            const stay = Number(w.getAttribute('data-stay')) || 0;
            if (kind === 'SIGHT') {
                sightCount++;
                sight += stay;
                const num = $('.order-num', w);
                if (num) num.textContent = sightCount;
            } else {
                brk += stay;
            }
            // 첫 자리는 이동 구간 자체가 없다
            const leg = $('.leg-note', w);
            if (leg) leg.classList.toggle('hidden', i === 0);
        });

        // 순서가 바뀌면 이동 추정도 달라진다 — 저장 전까지는 옛 값을 지운다
        if (dirty) {
            $$('.leg-note span', panel).forEach((s) => {
                s.textContent = '저장하면 이동시간을 다시 계산해요';
                s.classList.remove('text-error');
                s.classList.add('text-text-muted');
            });
        }

        const setText = (sel, v) => { const e = $(sel, panel); if (e) e.textContent = v; };
        setText('.budget-sight', minutesText(sight));
        setText('.budget-break', minutesText(brk));
        setText('.budget-used', minutesText(sight + brk + movedMinutes(panel)));

        const bar = $('.budget-bar-sight', panel);
        if (bar) bar.style.width = Math.min(100, Math.round(sight * 100 / 480)) + '%';
        const barBreak = $('.budget-bar-break', panel);
        if (barBreak) barBreak.style.width = Math.min(100, Math.round(brk * 100 / 480)) + '%';

        const empty = $('.empty-day', panel);
        if (empty) {
            empty.classList.toggle('hidden', sightCount > 0);
            empty.classList.toggle('flex', sightCount === 0);
        }
    }

    /** 화면에 남아 있는 이동 합계(서버가 계산한 값). 저장 전 편집 중에는 그대로 둔다 */
    function movedMinutes(panel) {
        const el = $('.budget-move', panel);
        if (!el) return 0;
        const text = el.textContent || '';
        const h = /(\d+)\s*시간/.exec(text);
        const m = /(\d+)\s*분/.exec(text);
        return (h ? Number(h[1]) * 60 : 0) + (m ? Number(m[1]) : 0);
    }

    function changeStay(wrap, delta) {
        const cur = Number(wrap.getAttribute('data-stay')) || 0;
        const next = Math.min(STAY_MAX, Math.max(STAY_MIN, cur + delta));
        if (next === cur) return;
        wrap.setAttribute('data-stay', next);
        const text = $('.stay-text', wrap);
        if (text) text.textContent = minutesText(next);
        markDirty();
        refreshPanel(panelOf(wrap));
    }

    function removeItem(wrap) {
        const panel = panelOf(wrap);
        wrap.remove();
        markDirty();
        refreshPanel(panel);
        renderMap();
    }

    function move(wrap, dir) {
        const sibling = dir < 0 ? wrap.previousElementSibling : wrap.nextElementSibling;
        if (!sibling || !sibling.classList.contains('trip-item-wrap')) return;
        if (dir < 0) {
            wrap.parentNode.insertBefore(wrap, sibling);
        } else {
            wrap.parentNode.insertBefore(sibling, wrap);
        }
        markDirty();
        refreshPanel(panelOf(wrap));
        renderMap();
    }

    /* =========================================================
       저장 · 확정
       ========================================================= */

    function collectDays() {
        return $$('.trip-items').map((list) => ({
            dayIndex: Number(list.getAttribute('data-day')),
            items: $$('.trip-item-wrap', list).map((w) => ({
                kind: w.getAttribute('data-kind'),
                name: $('.item-name', w) ? $('.item-name', w).textContent.trim() : w.getAttribute('data-name'),
                category: w.getAttribute('data-category') || null,
                dataType: w.getAttribute('data-datatype') || null,
                sage: w.getAttribute('data-sage') === 'true',
                attractionId: numOrNull(w.getAttribute('data-attraction-id')),
                image: w.getAttribute('data-image') || null,
                addr: w.getAttribute('data-addr') || null,
                lat: numOrNull(w.getAttribute('data-lat')),
                lng: numOrNull(w.getAttribute('data-lng')),
                stayMinutes: Number(w.getAttribute('data-stay')) || 30,
                hoursUnverified: w.getAttribute('data-hours-unverified') === 'true',
                description: w.getAttribute('data-description') || null,
                note: null,
            })),
        }));
    }

    function numOrNull(v) {
        if (v === null || v === undefined || v === '' || v === 'null') return null;
        const n = Number(v);
        return Number.isFinite(n) ? n : null;
    }

    async function save() {
        setSaveState('dirty', '저장 중…');
        const r = await postJson('/api/trip/' + tripId + '/save', { version: version, days: collectDays() });

        if (r.ok && r.data.ok) {
            dirty = false;
            version = r.data.version;
            setSaveState(null, '저장됨');
            // 서버가 다시 계산한 이동시간·순서를 그대로 보기 위해 다시 읽는다
            window.location.reload();
            return;
        }
        // 충돌이든 실패든 편집 내용은 화면에 그대로 남긴다(PRD F-09)
        setSaveState('failed', r.status === 409 ? '다른 곳에서 먼저 저장됨' : '저장 실패');
        alert(r.data.message || '저장하지 못했어요. 편집한 내용은 그대로 남아 있어요.');
    }

    async function confirmPlan() {
        if (dirty) {
            alert('저장하지 않은 변경이 있어요. 먼저 저장한 뒤 확정해 주세요.');
            return;
        }
        const r = await postJson('/api/trip/' + tripId + '/confirm', {});
        if (r.ok && r.data.ok) {
            version = r.data.version;
            const label = $('#confirm-label');
            if (label) label.textContent = '확정됨';
            setSaveState(null, '계획 확정됨');
            return;
        }
        alert(r.data.message || '확정하지 못했어요.');
    }

    async function renameTrip(title) {
        if (!title || !title.trim()) return;
        const r = await postJson('/api/trip/' + tripId + '/title', { title: title });
        if (r.ok && r.data.ok) version = r.data.version;
    }

    /* =========================================================
       먹거리 — 주변 먹거리 찾기
       ========================================================= */

    function openFoodSheet() {
        const sheet = $('#food-sheet');
        sheet.classList.remove('hidden');
        sheet.classList.add('flex');
    }

    function closeFoodSheet() {
        const sheet = $('#food-sheet');
        sheet.classList.add('hidden');
        sheet.classList.remove('flex');
    }

    async function loadFoodCandidates(day) {
        const list = $('#food-sheet-list');
        const base = $('#food-sheet-base');
        list.textContent = '';
        base.textContent = '기준점을 불러오는 중…';
        openFoodSheet();

        let data;
        try {
            const res = await fetch('/api/trip/' + tripId + '/food-candidates?day=' + day);
            data = await res.json();
        } catch (e) {
            base.textContent = '후보를 불러오지 못했어요.';
            return;
        }

        base.textContent = data.baseName
            ? 'Day ' + day + ' 마지막 장소 “' + data.baseName + '” 주변'
            : 'Day ' + day + ' — 담긴 장소가 없어 지역 전체에서 찾았어요';

        const items = data.candidates || [];
        if (!items.length) {
            const p = document.createElement('p');
            p.className = 'font-caption text-caption text-text-muted py-6 text-center';
            p.textContent = '이 지역에 적재된 먹거리 데이터가 아직 없어요.';
            list.appendChild(p);
            return;
        }

        items.forEach((c) => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'food-candidate';

            const body = document.createElement('span');
            body.className = 'flex-1 min-w-0 flex flex-col gap-0.5';

            const line = document.createElement('span');
            line.className = 'flex items-center gap-2 flex-wrap';
            const name = document.createElement('span');
            name.className = 'font-semibold';
            name.textContent = c.name;
            line.appendChild(name);
            if (c.sage) {
                const badge = document.createElement('span');
                badge.className = 'badge-sage';
                badge.textContent = '착한가격업소';
                line.appendChild(badge);
            }
            if (c.priceText) {
                const price = document.createElement('span');
                price.className = 'font-caption text-caption text-sage';
                price.textContent = c.priceText;
                line.appendChild(price);
            }
            body.appendChild(line);

            const sub = document.createElement('span');
            sub.className = 'font-caption text-caption text-text-muted';
            // 거리를 모르는 곳을 가까운 것처럼 쓰지 않는다
            sub.textContent = [c.category, c.addr, c.distanceText || '거리 모름'].filter(Boolean).join(' · ');
            body.appendChild(sub);

            btn.appendChild(body);

            const pick = document.createElement('span');
            pick.className = 'font-caption text-caption text-primary shrink-0';
            pick.textContent = '담기';
            btn.appendChild(pick);

            btn.addEventListener('click', () => registerFood(day, c));
            list.appendChild(btn);
        });
    }

    async function registerFood(day, c) {
        const r = await postJson('/api/trip/' + tripId + '/food', {
            dayIndex: day,
            name: c.name,
            addr: c.addr,
            sage: !!c.sage,
            priceText: c.priceText,
            lat: c.lat,
            lng: c.lng,
        });
        if (r.ok && r.data.ok) {
            closeFoodSheet();
            window.location.reload();
            return;
        }
        alert(r.data.message || '먹거리를 담지 못했어요.');
    }

    /* =========================================================
       숙소 직접 등록
       ========================================================= */

    function openStayForm(box, day) {
        if ($('.stay-form', box)) return;

        const form = document.createElement('div');
        form.className = 'stay-form w-full flex flex-col gap-2 mt-2';
        form.innerHTML =
            '<input class="stay-input-name w-full h-10 px-3 rounded-lg bg-surface-alt border border-border" placeholder="숙소 이름">' +
            '<input class="stay-input-addr w-full h-10 px-3 rounded-lg bg-surface-alt border border-border" placeholder="주소 (선택)">' +
            '<input class="stay-input-url w-full h-10 px-3 rounded-lg bg-surface-alt border border-border" placeholder="예약 페이지 링크 (선택, http로 시작)">' +
            '<div class="flex gap-2"><button type="button" class="stay-save btn-primary h-10 px-4 text-caption">등록</button>' +
            '<button type="button" class="stay-cancel btn-secondary h-10 px-4 text-caption">취소</button></div>' +
            '<p class="font-caption text-[12px] text-text-muted leading-relaxed">' +
            '좌표는 아직 직접 입력받지 않아요 — 등록해도 동선에는 반영되지 않고, 그 사실을 화면에 표시합니다.</p>';

        box.appendChild(form);
        $('.stay-cancel', form).addEventListener('click', () => form.remove());
        $('.stay-save', form).addEventListener('click', async () => {
            const name = $('.stay-input-name', form).value;
            if (!name.trim()) {
                alert('숙소 이름을 넣어주세요.');
                return;
            }
            const r = await postJson('/api/trip/' + tripId + '/stay', {
                dayIndex: day,
                name: name,
                addr: $('.stay-input-addr', form).value,
                url: $('.stay-input-url', form).value,
                lat: null,
                lng: null,
            });
            if (r.ok && r.data.ok) {
                window.location.reload();
                return;
            }
            alert(r.data.message || '숙소를 등록하지 못했어요.');
        });
    }

    /* =========================================================
       동선 지도 (카카오맵)
       ========================================================= */

    let map = null;
    let overlays = [];

    function clearOverlays() {
        overlays.forEach((o) => o.setMap(null));
        overlays = [];
    }

    function activeStops() {
        const panel = $('.day-panel:not(.hidden)');
        if (!panel) return [];
        return $$('.trip-item-wrap', panel)
            .filter((w) => w.getAttribute('data-kind') === 'SIGHT')
            .map((w) => ({ lat: numOrNull(w.getAttribute('data-lat')), lng: numOrNull(w.getAttribute('data-lng')) }))
            .filter((s) => s.lat !== null && s.lng !== null);
    }

    function renderMap() {
        const empty = $('#trip-map-empty');
        const stops = activeStops();
        if (empty) empty.classList.toggle('hidden', stops.length > 0);
        if (!map) return;

        clearOverlays();
        if (!stops.length) return;

        const positions = stops.map((s) => new kakao.maps.LatLng(s.lat, s.lng));

        if (positions.length > 1) {
            const casing = new kakao.maps.Polyline({
                path: positions, strokeWeight: 9, strokeColor: '#FFFFFF', strokeOpacity: 0.9, zIndex: 1,
            });
            casing.setMap(map);
            overlays.push(casing);

            const line = new kakao.maps.Polyline({
                path: positions, strokeWeight: 5, strokeColor: '#F26B4A', strokeOpacity: 1, zIndex: 2,
            });
            line.setMap(map);
            overlays.push(line);
        }

        positions.forEach((pos, i) => {
            const m = new kakao.maps.CustomOverlay({
                position: pos, yAnchor: 0.5, zIndex: 4,
                content: '<div style="display:flex;align-items:center;justify-content:center;width:26px;height:26px;' +
                         'border-radius:50%;background:var(--accent);color:var(--on-accent);font-weight:700;font-size:13px;' +
                         'border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.35)">' + (i + 1) + '</div>',
            });
            m.setMap(map);
            overlays.push(m);
        });

        const bounds = new kakao.maps.LatLngBounds();
        positions.forEach((p) => bounds.extend(p));
        if (positions.length === 1) {
            map.setCenter(positions[0]);
            map.setLevel(5);
        } else {
            map.setBounds(bounds, 40, 40, 40, 40);
        }
    }

    function initMap() {
        const el = $('#trip-map');
        // 키가 없으면 SDK 자체가 로드되지 않는다 → 안내만 남기고 조용히 끝낸다
        if (!el || typeof kakao === 'undefined' || !kakao.maps) {
            renderMap();
            return;
        }
        kakao.maps.load(() => {
            map = new kakao.maps.Map(el, { center: new kakao.maps.LatLng(36.5, 127.9), level: 9 });
            renderMap();
        });
    }

    /* =========================================================
       초기화
       ========================================================= */

    function init() {
        root = $('main[data-trip-id]');
        if (!root) return;
        tripId = root.getAttribute('data-trip-id');
        version = Number(root.getAttribute('data-version')) || 1;

        $$('.day-tab').forEach((t) => {
            t.addEventListener('click', () => showDay(Number(t.getAttribute('data-day'))));
        });

        root.addEventListener('click', (e) => {
            const wrap = e.target.closest('.trip-item-wrap');
            if (wrap) {
                if (e.target.closest('.stay-plus')) return changeStay(wrap, STAY_STEP);
                if (e.target.closest('.stay-minus')) return changeStay(wrap, -STAY_STEP);
                if (e.target.closest('.del-btn')) return removeItem(wrap);
                if (e.target.closest('.move-up')) return move(wrap, -1);
                if (e.target.closest('.move-down')) return move(wrap, 1);
            }
            const foodBtn = e.target.closest('.food-find');
            if (foodBtn) return loadFoodCandidates(Number(foodBtn.getAttribute('data-day')));

            const stayBtn = e.target.closest('.stay-register');
            if (stayBtn) return openStayForm(stayBtn.closest('.stay-box'), Number(stayBtn.getAttribute('data-day')));
        });

        const saveBtn = $('#save-btn');
        if (saveBtn) saveBtn.addEventListener('click', save);

        const confirmBtn = $('#confirm-btn');
        if (confirmBtn) confirmBtn.addEventListener('click', confirmPlan);

        const title = $('#trip-title');
        if (title) title.addEventListener('change', () => renameTrip(title.value));

        const close = $('#food-sheet-close');
        if (close) close.addEventListener('click', closeFoodSheet);
        const sheet = $('#food-sheet');
        if (sheet) {
            sheet.addEventListener('click', (e) => { if (e.target === sheet) closeFoodSheet(); });
        }
        document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeFoodSheet(); });

        initMap();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
