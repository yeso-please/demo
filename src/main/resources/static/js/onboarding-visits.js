// 여행 다이어리 — 지역을 고르고 줄글로 적으면 그 글에서 경험을 읽는다.
//
// 설문이 아니라 기록이다. 태그를 고르게 하지 않고, 쓴 글에 밑줄을 그어
// "여기서 읽었어요"를 보여준다. 틀린 건 칩을 눌러 뺀다.
//
// 편 수를 강제하지 않는다 — 한 편만 써도 결과를 보고, 더 쓰면 덜 흔들린다.
(function () {
    'use strict';

    const VIEW_W = 800, VIEW_H = 1000;
    const SVG_NS = 'http://www.w3.org/2000/svg';

    const pick = document.getElementById('pick');
    const write = document.getElementById('write');
    const regionInput = document.getElementById('region-input');
    const regionList = document.getElementById('region-list');
    const openMapBtn = document.getElementById('open-map');
    const mapWrap = document.getElementById('map-wrap');
    const mapClose = document.getElementById('map-close');
    const svg = document.getElementById('visit-map');
    const mapLoading = document.getElementById('map-loading');

    const wRegion = document.getElementById('w-region');
    const wWhen = document.getElementById('w-when');
    const wNote = document.getElementById('w-note');
    const wSat = document.getElementById('w-sat');
    const wSave = document.getElementById('w-save');
    const wChange = document.getElementById('w-change');
    const marks = document.getElementById('marks');
    const readRow = document.getElementById('read-row');
    const readTags = document.getElementById('read-tags');

    const entriesBox = document.getElementById('entries');
    const entriesEmpty = document.getElementById('entries-empty');
    const countEl = document.getElementById('count');
    const doneBtn = document.getElementById('done');
    const doneHint = document.getElementById('done-hint');

    const names = {};              // sigCd -> 지역명
    let entries = [];              // 저장된 편
    let draft = null;              // 지금 쓰는 편
    let dropped = [];              // 사용자가 뺀 태그
    let mapReady = false;

    /* ---------- 지역 고르기 ---------- */

    async function loadNames() {
        try {
            const topo = await (await fetch('/geo/sig.json')).json();
            const key = Object.keys(topo.objects)[0];
            topo.objects[key].geometries.forEach((g) => {
                names[g.properties.SIG_CD] = g.properties.SIG_KOR_NM || g.properties.SIG_CD;
            });
        } catch (e) { /* 검색만 막히고 지도는 따로 시도한다 */ }
    }

    regionInput.addEventListener('input', () => {
        const q = regionInput.value.trim();
        regionList.innerHTML = '';
        if (!q) { regionList.classList.add('hidden'); return; }
        const hits = Object.keys(names).filter((c) => names[c].indexOf(q) >= 0).slice(0, 8);
        if (!hits.length) { regionList.classList.add('hidden'); return; }
        hits.forEach((c) => {
            const li = document.createElement('li');
            li.textContent = names[c];
            li.addEventListener('click', () => startDraft(c));
            regionList.appendChild(li);
        });
        regionList.classList.remove('hidden');
    });

    openMapBtn.addEventListener('click', async () => {
        mapWrap.classList.remove('hidden');
        if (!mapReady) { await renderMap(); mapReady = true; }
        mapWrap.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
    mapClose.addEventListener('click', () => mapWrap.classList.add('hidden'));

    async function renderMap() {
        let topo;
        try {
            topo = await (await fetch('/geo/sig.json')).json();
        } catch (e) {
            mapLoading.textContent = '지도를 불러오지 못했어요.';
            return;
        }
        const key = Object.keys(topo.objects)[0];
        const fc = topojson.feature(topo, topo.objects[key]);
        const projection = d3.geoMercator().fitSize([VIEW_W, VIEW_H], fc);
        const path = d3.geoPath(projection);

        const frag = document.createDocumentFragment();
        for (const f of fc.features) {
            const d = path(f);
            if (!d) continue;
            const cd = f.properties.SIG_CD;
            const p = document.createElementNS(SVG_NS, 'path');
            p.setAttribute('d', d);
            p.setAttribute('class', 'sig-path');
            p.setAttribute('data-sig-cd', cd);
            p.setAttribute('tabindex', '0');
            p.setAttribute('role', 'button');
            p.setAttribute('aria-label', names[cd] || cd);
            const t = document.createElementNS(SVG_NS, 'title');
            t.textContent = names[cd] || cd;
            p.appendChild(t);
            frag.appendChild(p);
        }
        svg.appendChild(frag);
        mapLoading.style.display = 'none';
        paintMap();

        svg.addEventListener('click', (e) => {
            const t = e.target.closest && e.target.closest('.sig-path');
            if (t) { startDraft(t.getAttribute('data-sig-cd')); mapWrap.classList.add('hidden'); }
        });
        svg.addEventListener('keydown', (e) => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            const t = e.target.closest && e.target.closest('.sig-path');
            if (t) { e.preventDefault(); startDraft(t.getAttribute('data-sig-cd')); mapWrap.classList.add('hidden'); }
        });
    }

    function paintMap() {
        svg.querySelectorAll('.sig-path.written').forEach((p) => p.classList.remove('written'));
        entries.forEach((v) => {
            const p = svg.querySelector('.sig-path[data-sig-cd="' + v.sigCd + '"]');
            if (p) p.classList.add('written');
        });
    }

    /* ---------- 쓰기 ---------- */

    function startDraft(sigCd, existing) {
        draft = existing || { sigCd: sigCd, satisfaction: 'good', note: '', tags: [], when: '' };
        dropped = [];
        wRegion.textContent = names[sigCd] || sigCd;
        wWhen.value = draft.when || '';
        wNote.value = draft.note || '';
        wSat.querySelectorAll('.sat-btn').forEach((b) =>
            b.classList.toggle('on', b.dataset.value === draft.satisfaction));

        pick.classList.add('hidden');
        write.classList.remove('hidden');
        regionInput.value = '';
        regionList.classList.add('hidden');
        readNote();
        wNote.focus();
    }

    function closeDraft() {
        draft = null;
        write.classList.add('hidden');
        pick.classList.remove('hidden');
        marks.innerHTML = '';
        readRow.classList.add('hidden');
    }

    wChange.addEventListener('click', closeDraft);

    wSat.addEventListener('click', (e) => {
        const b = e.target.closest('.sat-btn');
        if (!b || !draft) return;
        draft.satisfaction = b.dataset.value;
        wSat.querySelectorAll('.sat-btn').forEach((x) => x.classList.toggle('on', x === b));
    });

    /* ---------- 글에서 읽기 ---------- */

    function escapeHtml(s) {
        return s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
    }

    /** 본문 위에 겹쳐놓은 층에 밑줄만 그린다 — 글자는 아래 textarea 가 그린다 */
    function paintMarks(text, spans) {
        let html = '', at = 0;
        spans.forEach((sp) => {
            if (sp.start < at) return;                     // 겹침 방어
            html += escapeHtml(text.slice(at, sp.start));
            html += '<mark>' + escapeHtml(text.slice(sp.start, sp.end)) + '</mark>';
            at = sp.end;
        });
        html += escapeHtml(text.slice(at));
        // 마지막 줄바꿈이 잘리지 않게 — 안 넣으면 밑줄이 한 줄씩 밀린다
        marks.innerHTML = html + '\n';
    }

    let readTimer = null;
    function readNote() {
        clearTimeout(readTimer);
        readTimer = setTimeout(async () => {
            if (!draft) return;
            const text = wNote.value;
            draft.note = text;
            if (!text.trim()) {
                marks.innerHTML = '';
                readRow.classList.add('hidden');
                draft.tags = [];
                return;
            }
            let data;
            try {
                const res = await fetch('/api/onboarding/read', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ note: text })
                });
                data = await res.json();
            } catch (e) { return; }

            const kept = (data.tags || []).filter((t) => dropped.indexOf(t) < 0);
            draft.tags = kept;
            paintMarks(text, (data.spans || []).filter((sp) => kept.indexOf(sp.tag) >= 0));

            readTags.innerHTML = '';
            (data.tags || []).forEach((t) => {
                const chip = document.createElement('button');
                chip.type = 'button';
                chip.className = 'read-chip' + (dropped.indexOf(t) >= 0 ? ' off' : '');
                chip.textContent = t;
                chip.title = dropped.indexOf(t) >= 0 ? '다시 넣기' : '이건 빼기';
                chip.addEventListener('click', () => {
                    const i = dropped.indexOf(t);
                    if (i >= 0) dropped.splice(i, 1); else dropped.push(t);
                    readNote();
                });
                readTags.appendChild(chip);
            });
            readRow.classList.toggle('hidden', !(data.tags || []).length);
        }, 220);
    }

    wNote.addEventListener('input', readNote);
    wNote.addEventListener('scroll', () => { marks.scrollTop = wNote.scrollTop; });
    wWhen.addEventListener('input', () => { if (draft) draft.when = wWhen.value; });

    /* ---------- 저장 ---------- */

    wSave.addEventListener('click', () => {
        if (!draft) return;
        draft.note = wNote.value;
        draft.when = wWhen.value;
        const i = entries.findIndex((e) => e.sigCd === draft.sigCd);
        if (i >= 0) entries[i] = draft; else entries.push(draft);
        closeDraft();
        renderEntries();
        save();
    });

    function renderEntries() {
        entriesBox.innerHTML = '';
        entries.forEach((v) => {
            const card = document.createElement('div');
            card.className = 'entry';
            card.tabIndex = 0;

            const head = document.createElement('span');
            head.className = 'e-head';
            head.textContent = (names[v.sigCd] || v.sigCd) + (v.when ? ' · ' + v.when : '');

            const body = document.createElement('span');
            body.className = 'e-body';
            body.textContent = v.note || '(아직 안 적었어요)';

            const foot = document.createElement('span');
            foot.className = 'e-foot';
            foot.textContent = (v.tags || []).join(' · ');

            card.appendChild(head);
            card.appendChild(body);
            if ((v.tags || []).length) card.appendChild(foot);
            card.addEventListener('click', () => startDraft(v.sigCd, v));
            entriesBox.appendChild(card);
        });

        if (entries.length) {
            const add = document.createElement('div');
            add.className = 'entry-add';
            add.textContent = '+ 한 편 더 쓰기';
            add.addEventListener('click', () => { closeDraft(); regionInput.focus(); });
            entriesBox.appendChild(add);
        }

        entriesEmpty.style.display = entries.length ? 'none' : '';
        countEl.textContent = entries.length + '편';
        doneBtn.disabled = entries.length === 0;
        doneHint.textContent = entries.length === 0
            ? '한 편만 써도 볼 수 있어요.'
            : (entries.length === 1
                ? '한 편으로 읽었어요. 더 쓰면 결과가 덜 흔들립니다.'
                : entries.length + '편으로 읽어요.');
        paintMap();
    }

    /* ---------- 세션 저장 · 복원 ---------- */

    let saveTimer = null;
    function save() {
        clearTimeout(saveTimer);
        saveTimer = setTimeout(() => {
            fetch('/api/onboarding/visits', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(entries)
            }).catch(() => { /* 저장 실패해도 화면은 계속 쓸 수 있어야 한다 */ });
        }, 250);
    }

    doneBtn.addEventListener('click', () => {
        if (doneBtn.disabled) return;
        doneBtn.disabled = true;
        doneBtn.textContent = '읽는 중…';
        fetch('/api/onboarding/visits', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(entries)
        }).finally(() => {
            try { sessionStorage.setItem('discovery.reveal', '1'); } catch (e) { /* 무시 */ }
            window.location.href = '/map';
        });
    });

    (async function init() {
        await loadNames();
        try {
            const saved = await (await fetch('/api/onboarding/visits')).json();
            entries = Array.isArray(saved) ? saved : [];
        } catch (e) { entries = []; }
        renderEntries();

        // [다녀왔어요]로 들어왔으면 지역은 이미 정해져 있다 — 고르는 단계를 건너뛴다.
        // 방금 다녀온 사람에게 "어디 다녀오셨나요"를 다시 묻는 건 이상하다.
        const main = document.querySelector('main[data-course-sig]');
        const sig = main && main.getAttribute('data-course-sig');
        if (sig) {
            const existing = entries.find((e) => e.sigCd === sig);
            startDraft(sig, existing);
            // 이 화면에서는 코스를 바꾸는 게 아니라 지역을 바꾸는 것이므로 그대로 두되,
            // 처음 열릴 때 본문에 바로 커서가 가도록 한다
            wNote.focus();
        }
    })();
})();
