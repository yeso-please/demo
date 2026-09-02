// 가까운 나들이 — 위치 기준 반나절 코스 (PRD 5.3 ①).
//
// 하루 코스와 달리 '거리'가 1차 필터다. 지금 나갈지를 정하는 화면이라
// 취향보다 갈 수 있는지가 먼저다. 서버(discoverNearby)도 같은 순서로 고른다.
(function () {
    'use strict';

    const btn = document.getElementById('locate');
    const kmSel = document.getElementById('km');
    const originEl = document.getElementById('origin');
    const statusEl = document.getElementById('status');
    const listEl = document.getElementById('list');

    let origin = null;

    function setStatus(text) {
        statusEl.textContent = text;
        statusEl.style.display = text ? '' : 'none';
    }

    /** 출발 시각 — 사용자가 바꾸면 시간표 전체가 다시 그려진다 */
    function startMinutes() {
        const v = document.getElementById('start').value || '14:00';
        const [h, m] = v.split(':').map(Number);
        return h * 60 + m;
    }

    function hhmm(minutes) {
        const m = ((minutes % 1440) + 1440) % 1440;
        return String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
    }

    function durText(min) {
        if (!min) return '';
        const h = Math.floor(min / 60), m = min % 60;
        return h ? (m ? h + '시간 ' + m + '분' : h + '시간') : m + '분';
    }

    /**
     * 한 정거장 = 이동 줄 + 머무는 줄.
     * 이동시간만 보여주면 "13분 코스"로 읽혀서 나들이를 정할 수가 없다 —
     * 시간을 잡아먹는 건 이동이 아니라 머무는 시간이다.
     */

    /**
     * 도착 시각에 이 장소가 열려 있는지.
     *
     * '지금'이 아니라 <b>도착 시각</b>으로 본다 — 나들이는 앞으로 갈 계획이라
     * 지금 열려 있는지는 아무 의미가 없다. 시간표가 이미 도착 시각을 알고 있으니 그걸 쓴다.
     *
     * 서버가 읽어내지 못한 원문(계절·요일별)은 판정하지 않고 조용히 넘어간다.
     */
    function openState(s, arriveMin, weekday) {
        if (s.closedWeekday && s.closedWeekday === weekday) {
            return { cls: 'closed', text: '이 요일 휴무' };
        }
        if (s.alwaysOpen) return { cls: 'open', text: '상시 개방' };
        if (s.openMinutes == null || s.closeMinutes == null) return null;

        if (arriveMin < s.openMinutes) {
            return { cls: 'closed', text: hhmm(s.openMinutes) + ' 오픈' };
        }
        // 도착해서 머무는 동안 닫히면 그것도 알려야 한다
        const leave = arriveMin + (s.stayMinutes || 0);
        if (arriveMin >= s.closeMinutes) {
            return { cls: 'closed', text: hhmm(s.closeMinutes) + ' 마감' };
        }
        if (leave > s.closeMinutes) {
            return { cls: 'warn', text: hhmm(s.closeMinutes) + ' 마감 — 시간이 빠듯해요' };
        }
        return { cls: 'open', text: hhmm(s.closeMinutes) + ' 까지' };
    }

    function stopRows(s, clock, weekday) {
        const out = [];

        if (s.legMinutes > 0) {
            const move = document.createElement('div');
            move.className = 'nb-move';
            move.textContent = '↓ 이동 ' + s.legMinutes + '분';
            out.push(move);
        }

        const row = document.createElement('div');
        row.className = 'nb-stop';

        const time = document.createElement('span');
        time.className = 'nb-time';
        time.textContent = hhmm(clock);

        // 썸네일 — 없으면 자리를 비워 정렬을 맞춘다(빈 회색 상자를 넣지 않는다)
        const thumb = document.createElement('span');
        thumb.className = 'nb-thumb';
        if (s.image) {
            const im = document.createElement('img');
            im.src = s.image;
            im.alt = '';
            im.loading = 'lazy';
            im.addEventListener('error', () => thumb.classList.add('nb-thumb--empty'));
            thumb.appendChild(im);
        } else {
            thumb.classList.add('nb-thumb--empty');
        }

        const body = document.createElement('span');
        body.className = 'nb-body';

        const head = document.createElement('span');
        head.className = 'nb-head';
        const slot = document.createElement('span');
        slot.className = 'nb-slot';
        slot.textContent = s.slot || '';
        const name = document.createElement('span');
        name.className = 'nb-name';
        name.textContent = s.name || '';
        head.appendChild(slot);
        head.appendChild(name);
        body.appendChild(head);

        // 어떤 곳인지 — 없으면 아무것도 넣지 않는다(지어내지 않는다)
        if (s.description) {
            const desc = document.createElement('span');
            desc.className = 'nb-desc';
            desc.textContent = s.description;
            body.appendChild(desc);
        }

        const facts = document.createElement('span');
        facts.className = 'nb-facts';

        function fact(text, cls) {
            if (!text) return;
            const chip = document.createElement('span');
            chip.className = cls ? 'nb-fact nb-fact--' + cls : 'nb-fact';
            chip.textContent = text;
            facts.appendChild(chip);
        }

        const st = openState(s, clock, weekday);
        fact(s.stayMinutes ? durText(s.stayMinutes) + ' 머묾' : null);
        fact(s.priceText);
        if (st) fact(st.text, st.cls);
        body.appendChild(facts);

        row.appendChild(time);
        row.appendChild(thumb);
        row.appendChild(body);
        out.push(row);
        return out;
    }

    function card(item) {
        const r = item.region, p = item.plan;
        const el = document.createElement('article');
        el.className = 'nb-card';

        const top = document.createElement('div');
        top.className = 'nb-top';
        const nm = document.createElement('span');
        nm.className = 'nb-region';
        nm.textContent = r.name;
        const pv = document.createElement('span');
        pv.className = 'nb-prov';
        pv.textContent = r.province || '';
        top.appendChild(nm);
        top.appendChild(pv);

        // 코스 제목이 곧 분위기 한 줄 — 서버가 코스 내용에서 규칙으로 만든다(LLM 아님)
        const mood = document.createElement('p');
        mood.className = 'nb-mood';
        mood.textContent = p.title || '';

        const stops = document.createElement('div');
        stops.className = 'nb-stops';
        let clock = startMinutes();
        const weekday = ((new Date().getDay() + 6) % 7) + 1;   // 1=월 … 7=일 (서버와 같은 번호)
        let anyClosed = false;
        (p.stops || []).forEach((s) => {
            clock += (s.legMinutes || 0);
            const st = openState(s, clock, weekday);
            if (st && st.cls === 'closed') anyClosed = true;
            stopRows(s, clock, weekday).forEach((n) => stops.appendChild(n));
            clock += (s.stayMinutes || 0);
        });

        // 언제 끝나는지 — 나들이를 정하는 건 결국 이 한 줄이다
        const end = document.createElement('div');
        end.className = 'nb-end';
        end.textContent = p.totalText
            ? hhmm(clock) + '쯤 마무리 · 대략 ' + p.totalText
            : hhmm(clock) + '쯤 마무리';
        if (anyClosed) {
            end.textContent += ' · 이 시간엔 닫힌 곳이 있어요';
            end.classList.add('nb-end--warn');
        }

        const why = document.createElement('p');
        why.style.cssText = 'font-size:.8rem;color:var(--text-muted);line-height:1.6';
        why.textContent = r.reason || '';

        const meta = document.createElement('div');
        meta.className = 'nb-meta';
        [p.distanceText, p.costText]
            .filter(Boolean)
            .forEach((t) => { const s = document.createElement('span'); s.textContent = t; meta.appendChild(s); });

        const cta = document.createElement('a');
        cta.className = 'nb-cta';
        cta.href = '/course?sigCd=' + encodeURIComponent(r.sigCd) + '&auto=true';
        cta.textContent = '코스 열기';

        el.appendChild(top);
        el.appendChild(mood);
        el.appendChild(stops);
        el.appendChild(end);
        if (r.reason) el.appendChild(why);
        el.appendChild(meta);
        el.appendChild(cta);
        return el;
    }

    async function load() {
        if (!origin) return;
        setStatus('근처를 찾는 중…');
        listEl.innerHTML = '';
        let data;
        try {
            const qs = '?lat=' + origin.lat + '&lng=' + origin.lng + '&km=' + kmSel.value;
            const res = await fetch('/api/nearby' + qs);
            data = await res.json();
        } catch (e) {
            setStatus('근처를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.');
            return;
        }
        const items = data.items || [];
        if (!items.length) {
            setStatus('이 반경 안에서는 반나절로 묶을 만한 곳을 찾지 못했어요. 반경을 넓혀 보시겠어요?');
            return;
        }
        setStatus('');
        items.forEach((it) => listEl.appendChild(card(it)));
    }

    btn.addEventListener('click', async () => {
        btn.disabled = true;
        btn.textContent = '위치 확인 중…';
        const pos = typeof window.getCurrentPositionSafe === 'function'
            ? await window.getCurrentPositionSafe()
            : null;
        btn.disabled = false;
        btn.textContent = '내 위치로 찾기';

        if (!pos) {
            // 위치를 거부해도 화면이 죽지 않게 — 서울시청을 기준점으로 둔다
            origin = { lat: 37.5665, lng: 126.9780 };
            originEl.textContent = '위치를 못 받아 서울 도심 기준으로 보여드려요.';
        } else {
            origin = pos;
            originEl.textContent = '현재 위치 기준';
        }
        load();
    });

    kmSel.addEventListener('change', load);
    document.getElementById('start').addEventListener('change', () => { if (origin) load(); });

    // 이전에 받아둔 위치가 있으면 바로 보여준다
    const cached = typeof window.getCachedGeo === 'function' ? window.getCachedGeo() : null;
    if (cached) {
        origin = cached;
        originEl.textContent = '최근 위치 기준';
        load();
    }
})();
