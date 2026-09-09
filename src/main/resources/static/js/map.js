// 시군구 SVG 지도 + 지역 패널
// 흐름: 로딩 → 지도 렌더 → 호버/클릭/키보드/검색/다트 → fetch(/api/regions) → 패널 슬라이드 인
(function () {
    'use strict';

    const VIEW_W = 800;
    const VIEW_H = 1000;
    const SVG_NS = 'http://www.w3.org/2000/svg';

    let svg, panel, panelBody, coursePanel, coursePanelBody;
    const boundsByCode = {};   // SIG_CD → [[x0,y0],[x1,y1]]

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
        // 빛 레이어(#map-glow)가 지도 위에 오도록, 시군구는 전용 그룹 안에 넣는다
        (document.getElementById('map-base') || svg).appendChild(frag);
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

    /* ---------- 지역 선택 → 패널 ---------- */
    // 외부(추천 모달 등)에서 지역 선택 흐름을 재사용할 수 있게 노출
    window.selectRegion = selectRegion;

    async function selectRegion(sigCd) {
        if (!sigCd) return;
        closeCoursePanel();
        svg.querySelectorAll('.sig-path.selected').forEach((p) => {
            p.classList.remove('selected', 'selected-ignite');
        });
        const target = svg.querySelector('.sig-path[data-sig-cd="' + sigCd + '"]');
        if (target) {
            target.classList.add('selected');
            retrigger(target, 'selected-ignite');
        }

        // 지도를 확대하지 않고, 선택한 순간 지도만 살짝 밀어 패널이 펼쳐지는 여백을 만든다.
        const mapMain = document.getElementById('map-main');
        if (mapMain) mapMain.classList.add('region-selected');
        paintSelectedGlow(sigCd);
        burstAt(sigCd, Date.now() % 17);
        const flash = document.getElementById('map-reveal-flash');
        if (flash) retrigger(flash, 'active');

        const regionController = new AbortController();
        const regionTimeout = setTimeout(() => regionController.abort(), 10000);
        try {
            const res = await fetch('/api/regions/' + encodeURIComponent(sigCd), { signal: regionController.signal });
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const data = await res.json();
            fillPanel(data, sigCd);
            openPanel();
            showPicks(false);   // 지역을 골랐으면 지도에 집중
            return true;
        } catch (err) {
            console.error('[map] 지역 정보 로드 실패:', err);
            return false;
        } finally {
            clearTimeout(regionTimeout);
        }
    }

    function fillPanel(data, sigCd) {
        document.getElementById('panel-name').textContent = data.name || '';
        document.getElementById('panel-province').textContent = data.province || '';
        document.getElementById('panel-ai').textContent = data.aiSummary || '';
        fillDiscoveryNote(sigCd);

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

        // '이 지역으로 여행 떠나기' — 폼의 sigCd 를 지금 연 지역으로 맞춘다.
        // 자동 코스를 못 짜는 지역에서는 감춘다(빈 계획이 만들어지는 걸 막는다).
        const tripForm = panel.querySelector('.trip-start');
        if (tripForm) {
            const sigInput = tripForm.querySelector('.trip-start__sig');
            if (sigInput) sigInput.value = sigCd;
            tripForm.classList.toggle('hidden', data.dayPlanAvailable === false);
        }

        // '이 지역에서 하루 보내기' — 코스를 조립할 수 있는 지역에서만 내보낸다.
        // 눌러도 아무것도 안 나오는 버튼은 지역을 발견한 순간의 신뢰를 깎는다.
        const dayBtn = document.getElementById('panel-day');
        const dayNote = document.getElementById('panel-day-note');
        if (dayBtn) {
            const ok = data.dayPlanAvailable !== false;
            const recommended = discovered[sigCd];
            const officialHref = recommended && recommended.courseId
                ? '/course?sigCd=' + encodeURIComponent(sigCd)
                    + '&courseId=' + encodeURIComponent(recommended.courseId)
                : null;
            dayBtn.setAttribute('href', officialHref || (ok
                ? '/course?sigCd=' + encodeURIComponent(sigCd) + '&auto=true'
                : '#'));
            const dayLabel = document.getElementById('panel-day-label');
            if (dayLabel) dayLabel.textContent = officialHref ? '이 공식 코스 열기' : '이 지역에서 하루 보내기';
            // 안내 문구가 'hidden' 클래스로 숨겨져 있어 인라인 display 로는 못 되돌린다
            dayBtn.classList.toggle('hidden', !ok && !officialHref);
            if (dayNote) dayNote.classList.toggle('hidden', ok || !!officialHref);
        }
    }

    function openPanel() {
        const wasOpen = panel.classList.contains('open');
        panel.classList.add('open');
        if (wasOpen) retrigger(panel, 'open'); // 이미 열려 있으면 진입 애니메이션 재생
        retrigger(panelBody, 'stagger-fade-in');
    }
    function openCoursePanel() {
        const wasOpen = coursePanel.classList.contains('open');
        coursePanel.classList.add('open');
        if (wasOpen) retrigger(coursePanel, 'open');
        retrigger(coursePanelBody, 'stagger-fade-in');
    }
    function closeCoursePanel() {
        if (!coursePanel) return;
        coursePanel.classList.remove('open');
    }
    function closePanel() {
        panel.classList.remove('open');
        closeCoursePanel();
        const mapMain = document.getElementById('map-main');
        if (mapMain) mapMain.classList.remove('region-selected');
        svg.querySelectorAll('.sig-path.selected').forEach((p) => {
            p.classList.remove('selected', 'selected-ignite');
        });
        const selectedGlow = document.getElementById('map-selected-glow');
        if (selectedGlow) selectedGlow.textContent = '';
        showPicks(true);   // 선택 해제 → 다시 제안을 보여준다
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

    /* ---------- 내가 쓴 여행이 부른 코스 ----------
       다이어리에서 읽은 취향으로 채운다. 추천 단위는 행정구역이 아니라 TourAPI 공식 코스다.
       지역명은 위치 정보로만 작게 남기고, 코스 제목·subdetailoverview·subdetailimg를 전면에 둔다. */
    function showPicks(show) {
        const el = document.getElementById('my-picks');
        if (!el) return;
        el.style.opacity = show ? '' : '0';
        el.style.visibility = show ? '' : 'hidden';
    }

    function esc(v) {
        const d = document.createElement('div');
        d.textContent = v == null ? '' : String(v);
        return d.innerHTML;
    }

    function bindPickCard(card, candidate) {
        card.addEventListener('mouseenter', () => {
            const p = svg && svg.querySelector('.sig-path[data-sig-cd="' + candidate.sigCd + '"]');
            if (p) p.classList.add('course-preview');
        });
        card.addEventListener('mouseleave', () => {
            const p = svg && svg.querySelector('.sig-path[data-sig-cd="' + candidate.sigCd + '"]');
            if (p) p.classList.remove('course-preview');
        });
        card.addEventListener('click', () => selectCourse(candidate));
        card._courseCandidate = candidate;
        return card;
    }

    function pickCard(c) {
        const a = document.createElement('button');
        a.type = 'button';
        a.className = 'course-pick-card group bg-surface border-2 border-border rounded-xl overflow-hidden '
                    + 'hover:border-primary hover:-translate-y-[2px] transition-all duration-300 text-left w-full';
        a.setAttribute('data-sig-cd', c.sigCd);
        a.setAttribute('aria-label', (c.courseTitle || '추천 코스') + ' 코스 열기');

        // 목록 API의 지역 대표사진이 아니라 detailInfo가 준 코스 경유지 사진을 쓴다.
        const image = c.courseImage || c.image;
        const title = c.courseTitle || c.name;
        const cover = image
            ? '<div class="relative h-[136px] bg-surface-alt overflow-hidden">'
                + '<img src="' + esc(image) + '" alt="' + esc(title) + '" loading="lazy" '
                + 'class="w-full h-full object-cover group-hover:scale-[1.04] transition-transform duration-500"/>'
                + '<div class="absolute inset-0 course-pick-shade"></div>'
                + '<span class="absolute top-2 left-2.5 course-origin-badge">TourAPI 공식 코스</span>'
                + '<h3 class="absolute bottom-2.5 left-3 right-3 font-card-title text-card-title text-text-primary leading-snug">'
                + esc(title) + '</h3>'
              + '</div>'
            : '<div class="px-3 pt-3"><span class="course-origin-badge">TourAPI 공식 코스</span>'
                + '<h3 class="font-card-title text-card-title text-text-primary leading-snug mt-2">'
                + esc(title) + '</h3></div>';

        const tags = (c.matchedTags || []).slice(0, 3)
            .map((t) => '<span class="px-2 py-0.5 rounded-full bg-accent-soft text-accent-hover">' + esc(t) + '</span>')
            .join('');
        const stops = (c.courseStops || []).slice(0, 3)
            .map((s) => '<span>' + esc(s) + '</span>')
            .join('<i aria-hidden="true">→</i>');

        a.innerHTML = cover
            + '<div class="p-3 flex flex-col gap-1.5">'
                + '<p class="course-pick-location">' + esc(c.province) + ' · ' + esc(c.name) + '</p>'
                + '<p class="font-caption text-caption text-text-primary leading-relaxed line-clamp-3">'
                    + esc(c.courseSubtitle || c.reason || c.description) + '</p>'
                + (stops ? '<div class="course-stop-line" aria-label="코스 경유지">' + stops + '</div>' : '')
                + (tags ? '<div class="flex gap-1 flex-wrap font-caption text-caption">' + tags + '</div>' : '')
                + '<p class="font-caption text-[11px] text-text-muted leading-relaxed">' + esc(c.reason || '') + '</p>'
            + '</div>';

        // 카드에 손을 올리면 그 코스가 놓인 지역만 지도에서 은은하게 응답한다.
        return bindPickCard(a, c);
    }

    function selectCourse(c) {
        if (!c || !c.sigCd) return;
        closePanel();
        svg.querySelectorAll('.sig-path.selected').forEach((p) => p.classList.remove('selected', 'selected-ignite'));
        const target = svg.querySelector('.sig-path[data-sig-cd="' + c.sigCd + '"]');
        if (target) {
            target.classList.add('selected');
            retrigger(target, 'selected-ignite');
        }
        const mapMain = document.getElementById('map-main');
        if (mapMain) mapMain.classList.add('region-selected');
        paintSelectedGlow(c.sigCd);
        burstAt(c.sigCd, Date.now() % 17);
        const flash = document.getElementById('map-reveal-flash');
        if (flash) retrigger(flash, 'active');

        document.getElementById('course-preview-title').textContent = c.courseTitle || c.name || '추천 코스';
        document.getElementById('course-preview-location').textContent = (c.province || '') + (c.name ? ' · ' + c.name : '');
        document.getElementById('course-preview-reason').textContent = c.reason || '당신이 남긴 여행 기록의 결을 바탕으로 고른 코스예요.';
        document.getElementById('course-preview-description').textContent = c.courseSubtitle || c.description || '코스의 경유지를 따라 지역의 이야기를 발견해보세요.';
        const image = document.getElementById('course-preview-image');
        if (c.courseImage || c.image) {
            image.src = c.courseImage || c.image;
            image.alt = c.courseTitle || c.name || '추천 코스 사진';
            image.classList.remove('hidden');
        } else image.classList.add('hidden');
        const stops = document.getElementById('course-preview-stops');
        stops.innerHTML = '';
        (c.courseStops || []).forEach((stop, index) => {
            const row = el('div', 'flex items-start gap-3 p-3 bg-surface-alt border border-border rounded-lg');
            row.appendChild(el('span', 'w-6 h-6 rounded-full bg-accent-soft text-primary flex items-center justify-center font-semibold text-xs shrink-0', String(index + 1)));
            row.appendChild(el('span', 'font-body-main text-caption text-text-primary leading-relaxed', stop));
            stops.appendChild(row);
        });
        const go = document.getElementById('course-preview-go');
        go.href = '/course?sigCd=' + encodeURIComponent(c.sigCd)
            + (c.courseId ? '&courseId=' + encodeURIComponent(c.courseId) : '');
        openCoursePanel();
        showPicks(false);
    }

    async function initPicks() {
        const list = document.getElementById('picks-list');
        const empty = document.getElementById('picks-empty');
        const basis = document.getElementById('picks-basis');
        if (!list) return;

        let data;
        try {
            const res = await fetch('/api/onboarding/dna?limit=6');
            if (!res.ok) return;              // 조용히 빈 상태로 둔다
            data = await res.json();
        } catch (e) { return; }

        if (!data.enough) {
            const copy = document.getElementById('picks-empty-copy');
            const remaining = Number(data.remaining) || 1;
            if (copy) copy.textContent = '여행 기록을 ' + remaining
                + '편 더 남기면, 그 결에 맞는 공식 코스를 여기에 켜 드려요.';
            return;
        }
        if (!(data.candidates || []).length) {
            const copy = document.getElementById('picks-empty-copy');
            if (copy) copy.textContent = '기록은 충분해요. 어울리는 공식 코스를 찾고 있어요.';
            return;
        }

        data.candidates.forEach((c) => list.appendChild(pickCard(c)));
        if (empty) empty.style.display = 'none';
        if (basis) basis.textContent = data.visitedCount + '편으로 읽었어요';

        // 방은 후보를 비교하는 자리라 한 곳만으로는 만들 수 없다.
        // 위에 보이는 순서 그대로(최대 3곳) 넘긴다 — 화면과 방의 후보가 달라지면 안 된다.
        const form = document.getElementById('ask-friends');
        const inputs = document.getElementById('ask-inputs');
        if (form && inputs && data.candidates.length >= 2) {
            inputs.innerHTML = '';
            data.candidates.slice(0, 3).forEach((c) => {
                const i = document.createElement('input');
                i.type = 'hidden';
                i.name = 'sigCd';
                i.value = c.sigCd;
                inputs.appendChild(i);
            });
            form.classList.remove('hidden');
        }

        // 추천 코스가 로드되면 항상 목록을 일정한 속도로 자동 순환한다.
        const scroller = document.getElementById('picks-scroll');
        const picks = document.getElementById('my-picks');
        if (scroller && picks) {
            picks.classList.add('auto-reveal');
            startPicksAutoScroll(scroller, picks);
        }
    }

    function startPicksAutoScroll(scroller, picks) {
        const list = document.getElementById('picks-list');
        if (!list || list.dataset.autoLoop === 'true') return;
        const cards = Array.from(list.children);
        if (cards.length < 2) return;

        // scrollTop은 absolute/flex 조합에서 브라우저마다 viewport 계산 시점이 달라
        // 값만 변하고 화면이 안 움직일 수 있다. 동일한 카드 묶음을 한 번 더 붙여
        // CSS transform으로 끊김 없이 순환시키면 레이아웃과 무관하게 항상 보인다.
        const gap = parseFloat(getComputedStyle(list).rowGap || getComputedStyle(list).gap) || 0;
        const loopDistance = list.scrollHeight + gap;
        cards.forEach((card) => {
            const clone = card.cloneNode(true);
            clone.setAttribute('aria-hidden', 'true');
            clone.setAttribute('tabindex', '-1');
            bindPickCard(clone, card._courseCandidate);
            list.appendChild(clone);
        });
        list.dataset.autoLoop = 'true';
        list.style.setProperty('--picks-loop-distance', loopDistance + 'px');
        list.classList.add('picks-auto-track');
    }

    /* ---------- 무작위로 한 곳 보기 (대포) ----------
       대포로 쏜 사람이 포물선을 그리며 날아가 떨어지고, 떨어진 지역에 불이 켜진다.
       고르는 방식은 예전 룰렛과 똑같은 단순 무작위다 — 연출만 바꿨다.
       (저평가 지수가 생기면 가중 샘플링으로 교체할 자리) */

    const SHOT_FLIGHT_MS = 980;   // 포구를 떠나 땅에 닿기까지
    const SHOT_SETTLE_MS = 620;   // 떨어진 뒤 반동이 잦아들기까지
    const SHOT_HOLD_MS   = 900;   // 결과를 보는 동안 그 자리에 두는 시간
    let throwing = false;
    let shotFade = null;          // 페이드아웃(다음 발사 때 취소해야 한다)

    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

    /** 떨어질 좌표(viewBox 기준). 늘 정중앙이면 기계적으로 보여 살짝만 흔든다. */
    function shotPoint(sigCd) {
        const b = boundsByCode[sigCd];
        if (!b) return { x: VIEW_W / 2, y: VIEW_H / 2 };
        const cx = (b[0][0] + b[1][0]) / 2;
        const cy = (b[0][1] + b[1][1]) / 2;
        const jx = (b[1][0] - b[0][0]) * 0.08;
        const jy = (b[1][1] - b[0][1]) * 0.08;
        return {
            x: cx + (Math.random() * 2 - 1) * jx,
            y: cy + (Math.random() * 2 - 1) * jy,
        };
    }

    /** 2차 베지에 위의 한 점. 다트와 꼬리가 완전히 같은 곡선을 지나야 한다. */
    function bezier(a, c, b, u) {
        const m = 1 - u;
        return {
            x: m * m * a.x + 2 * m * u * c.x + u * u * b.x,
            y: m * m * a.y + 2 * m * u * c.y + u * u * b.y,
        };
    }

    /** 명중 순간의 충격파. 여러 겹을 시간차로 퍼뜨려야 '박혔다'로 읽힌다. */
    function shotImpact(t) {
        const rings = document.querySelectorAll('#shot-impact .shot-impact__ring');
        const spread = [30, 52, 74];
        rings.forEach((ring, i) => {
            ring.animate([
                { transform: `translate(${t.x}px, ${t.y}px) scale(2)`, opacity: i === 0 ? 1 : .55 },
                { transform: `translate(${t.x}px, ${t.y}px) scale(${spread[i] || 60})`, opacity: 0 },
            ], {
                duration: 460 + i * 170,
                delay: i * 70,
                easing: 'cubic-bezier(.12,.9,.25,1)',
                fill: 'forwards',
            });
        });

        const flash = document.getElementById('shot-flash');
        if (flash) {
            flash.animate([
                { transform: `translate(${t.x}px, ${t.y}px) scale(.4)`, opacity: .95 },
                { transform: `translate(${t.x}px, ${t.y}px) scale(2.6)`, opacity: 0 },
            ], { duration: 260, easing: 'ease-out', fill: 'forwards' });
        }

        // 지면을 따라 납작하게 번지는 먼지 — 위로 퍼지는 링과 달리 '떨어져 터졌다'로 읽힌다
        const dust = document.getElementById('shot-dust');
        if (dust) {
            dust.animate([
                { transform: `translate(${t.x}px, ${t.y}px) scale(3)`, opacity: .75 },
                { transform: `translate(${t.x}px, ${t.y}px) scale(40)`, opacity: 0 },
            ], { duration: 720, easing: 'cubic-bezier(.1,.85,.25,1)', fill: 'forwards' });
        }

        // 파편 14 개를 방사로 뿌린다. 길이·거리를 조금씩 흩어야 규칙적으로 안 보인다.
        const box = document.getElementById('shot-sparks');
        if (box) {
            box.innerHTML = '';
            for (let i = 0; i < 14; i++) {
                const a = (Math.PI * 2 * i) / 14 + Math.random() * 0.35;
                const near = 9, far = 38 + Math.random() * 34;
                const ln = document.createElementNS(SVG_NS, 'line');
                ln.setAttribute('class', 'shot-spark');
                ln.setAttribute('x1', (t.x + Math.cos(a) * near).toFixed(1));
                ln.setAttribute('y1', (t.y + Math.sin(a) * near).toFixed(1));
                ln.setAttribute('x2', (t.x + Math.cos(a) * (near + 9)).toFixed(1));
                ln.setAttribute('y2', (t.y + Math.sin(a) * (near + 9)).toFixed(1));
                box.appendChild(ln);
                ln.animate([
                    { transform: 'translate(0, 0)', opacity: 1 },
                    { transform: `translate(${Math.cos(a) * far}px, ${Math.sin(a) * far}px)`, opacity: 0 },
                ], { duration: 380 + Math.random() * 220, easing: 'cubic-bezier(.1,.8,.3,1)', fill: 'forwards' });
            }
        }
    }

    /** 포구 화염 — 짧고 크게 터졌다 사라진다 */
    function muzzleBlast(L, aim) {
        const flash = document.getElementById('muzzle-flash');
        const box = document.getElementById('shot-muzzle');
        if (flash) {
            flash.animate([
                { transform: `translate(${L.x}px, ${L.y}px) scale(.3)`, opacity: 1 },
                { transform: `translate(${L.x}px, ${L.y}px) scale(3.2)`, opacity: 0 },
            ], { duration: 300, easing: 'cubic-bezier(.1,.85,.3,1)', fill: 'forwards' });
        }
        if (!box) return;
        box.querySelectorAll('.muzzle-line').forEach((n) => n.remove());
        // 포신이 향한 쪽으로만 화염이 뻗는다 — 사방으로 뿌리면 폭발이지 발사가 아니다
        for (let i = 0; i < 7; i++) {
            const a = aim + (Math.random() - .5) * 1.1;
            const far = 46 + Math.random() * 40;
            const ln = document.createElementNS(SVG_NS, 'line');
            ln.setAttribute('class', 'muzzle-line');
            ln.setAttribute('x1', L.x.toFixed(1));
            ln.setAttribute('y1', L.y.toFixed(1));
            ln.setAttribute('x2', (L.x + Math.cos(a) * 16).toFixed(1));
            ln.setAttribute('y2', (L.y + Math.sin(a) * 16).toFixed(1));
            box.appendChild(ln);
            ln.animate([
                { transform: 'translate(0, 0)', opacity: 1 },
                { transform: `translate(${Math.cos(a) * far}px, ${Math.sin(a) * far}px)`, opacity: 0 },
            ], { duration: 280 + Math.random() * 160, easing: 'cubic-bezier(.1,.85,.3,1)', fill: 'forwards' });
        }
    }

    /** 포연 — 궤적 앞쪽에 뭉게뭉게 남는다 */
    function trailSmoke(pts) {
        const box = document.getElementById('shot-smoke');
        if (!box) return;
        box.innerHTML = '';
        for (let i = 0; i < 6; i++) {
            const pt = pts[Math.round((i + 1) / 8 * (pts.length - 1))];
            if (!pt) continue;
            const c = document.createElementNS(SVG_NS, 'circle');
            c.setAttribute('class', 'shot-smoke');
            c.setAttribute('cx', pt.x.toFixed(1));
            c.setAttribute('cy', pt.y.toFixed(1));
            c.setAttribute('r', (5 + Math.random() * 4).toFixed(1));
            box.appendChild(c);
            c.animate([
                { transform: 'scale(.4)', opacity: 0 },
                { transform: 'scale(1.2)', opacity: .34, offset: .25 },
                { transform: `scale(${(2.6 + Math.random()).toFixed(2)})`, opacity: 0 },
            ], { duration: 900 + Math.random() * 400, delay: i * 55, easing: 'ease-out', fill: 'forwards' });
        }
    }

    /**
     * 대포를 쏜다. 착탄 순간에 resolve 되므로 호출부가 바로 지역을 켤 수 있다.
     *
     * 궤적은 진짜 포물선이다 — 수평 속도는 일정하고 높이만 2차로 변한다.
     * 그래서 베지에 파라미터를 시간에 그대로(선형) 물린다. 여기에 ease 를 먹이면
     * 포물선이 일그러져 '손으로 던진 것'처럼 보인다.
     */
    async function fireCannon(t) {
        const layer = document.getElementById('map-shot');
        const body = document.getElementById('shot-body');
        const shadow = document.getElementById('shot-shadow');
        const trail = document.getElementById('shot-trail');
        if (!layer || !body || !shadow) return;

        // 직전 발사의 페이드아웃이 fill:forwards 로 남아 있으면 새 포탄이 보이지 않는다
        if (shotFade) {
            shotFade.cancel();
            shotFade = null;
        }
        layer.classList.add('is-active');
        body.classList.add('is-flying');     // 팔다리 버둥거림 시작
        if (svg) svg.classList.add('is-throwing');

        // 포는 지도 오른쪽 아래 구석에 둔다(대개 바다라 지역을 가리지 않는다)
        const L = { x: VIEW_W - 46, y: VIEW_H - 26 };
        // 정점을 높일수록 곡사포, 낮추면 직사포가 된다
        const ctrl = { x: (L.x + t.x) / 2, y: Math.min(L.y, t.y) - 300 };

        const STEPS = 16;
        const SPINS = 2.75;             // 사람이 정신없이 구르는 편이 재밌다
        const pts = [];
        for (let i = 0; i <= STEPS; i++) pts.push(bezier(L, ctrl, t, i / STEPS));

        muzzleBlast(L, Math.atan2(ctrl.y - L.y, ctrl.x - L.x));
        trailSmoke(pts);

        if (trail) {
            trail.setAttribute('d',
                `M${L.x.toFixed(1)} ${L.y.toFixed(1)} Q${ctrl.x.toFixed(1)} ${ctrl.y.toFixed(1)} ${t.x.toFixed(1)} ${t.y.toFixed(1)}`);
            const len = trail.getTotalLength();
            trail.style.strokeDasharray = `${len * .22} ${len}`;
            trail.animate([
                { strokeDashoffset: len * .22, opacity: 0 },
                { opacity: .9, offset: .2 },
                { strokeDashoffset: -len, opacity: 0 },
                // fill 을 남기면 다음 발사 때 dashoffset 이 그대로 걸려 꼬리가 안 보인다
            ], { duration: SHOT_FLIGHT_MS + 140, easing: 'linear' });
        }

        const frames = [];
        const shadowFrames = [];
        for (let i = 0; i <= STEPS; i++) {
            const p = i / STEPS;
            const pt = pts[i];
            // 그림자는 아치를 그리지 않고 지면을 직선으로 간다 — 이 차이가 '높이'를 만든다
            const gx = L.x + (t.x - L.x) * p;
            const gy = L.y + (t.y - L.y) * p;
            const lift = Math.max(0, Math.min(1, (gy - pt.y) / 300));   // 0(지면) ~ 1(정점)
            frames.push({
                offset: p,
                transform: `translate(${pt.x.toFixed(1)}px, ${pt.y.toFixed(1)}px) `
                    + `scale(${(1 + 1.25 * lift).toFixed(2)}) rotate(${(-360 * SPINS * (1 - p)).toFixed(1)}deg)`,
                opacity: 1,
            });
            shadowFrames.push({
                offset: p,
                transform: `translate(${gx.toFixed(1)}px, ${gy.toFixed(1)}px) scale(${(1 + 1.6 * lift).toFixed(2)})`,
                opacity: (0.5 - 0.34 * lift).toFixed(2),   // 높이 뜰수록 옅고 크게
            });
        }

        shadow.animate(shadowFrames, { duration: SHOT_FLIGHT_MS, easing: 'linear', fill: 'forwards' });
        await body.animate(frames, { duration: SHOT_FLIGHT_MS, easing: 'linear', fill: 'forwards' }).finished;

        shotImpact(t);
        body.classList.remove('is-flying');   // 땅에 닿는 순간 팔다리가 멎는다
        if (svg) {
            svg.classList.remove('is-throwing');
            retrigger(svg, 'is-struck');
        }

        // 착지 반동. 사람은 다트처럼 떨리지 않는다 — 납작하게 눌렸다가 한 번 튀고 선다.
        // 가로세로를 반대로 늘리는 스쿼시가 무게를 만든다(가로만 늘리면 고무공이 된다).
        const at = (sx, sy, dy, rot) =>
            `translate(${t.x}px, ${(t.y + dy).toFixed(1)}px) scale(${sx}, ${sy}) rotate(${rot}deg)`;
        body.animate([
            { transform: at(1.5, .58, 6, 0), offset: 0 },      // 부딪히며 납작하게 뭉개지고
            { transform: at(.82, 1.24, -14, -8), offset: .15 },// 크게 튀어오르고
            { transform: at(1.18, .86, 2, 5), offset: .32 },   // 두 번째 착지
            { transform: at(.92, 1.1, -6, 6), offset: .46 },   // 작게 한 번 더 튀고
            { transform: at(1.08, .94, 1, -4), offset: .62 },
            { transform: at(.98, 1.03, -1, 3), offset: .76 },  // 휘청
            { transform: at(1.02, .99, 0, -1.5), offset: .89 },
            { transform: at(1, 1, 0, 0), offset: 1 },
        ], { duration: SHOT_SETTLE_MS, easing: 'ease-out', fill: 'forwards' });
    }

    /** 결과를 충분히 본 뒤 사람을 걷는다 (지역의 불은 그대로 남는다) */
    function clearShot() {
        const layer = document.getElementById('map-shot');
        if (!layer) return;
        shotFade = layer.animate([{ opacity: 1 }, { opacity: 0 }],
            { duration: 380, easing: 'ease-out', fill: 'forwards' });
        shotFade.finished.then(() => layer.classList.remove('is-active'))
            .catch(() => { /* 다음 던지기가 끼어들면 무시 */ });
    }

    async function pickRandom() {
        if (throwing) return;
        const paths = Array.from(svg.querySelectorAll('.sig-path'));
        if (!paths.length) return;

        throwing = true;
        const btn = document.getElementById('map-shuffle');
        if (btn) {
            btn.disabled = true;
            btn.classList.add('is-throwing');
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
            await fireCannon(shotPoint(finalSigCd));
        }

        await selectRegion(finalSigCd);

        if (!reduceMotion) {
            sleep(SHOT_HOLD_MS).then(clearShot);
        }

        if (btn) {
            btn.disabled = false;
            btn.classList.remove('is-throwing');
        }
        throwing = false;
    }

    /* ---------- 초기화 ---------- */
    async function init() {
        svg = document.getElementById('korea-map');
        panel = document.getElementById('region-panel');
        panelBody = document.getElementById('panel-body');
        coursePanel = document.getElementById('course-preview-panel');
        coursePanelBody = document.getElementById('course-preview-body');
        if (!svg || !panel) return;

        initSearch();
        initPicks();
        await renderMap();

        const closeBtn = document.getElementById('panel-close');
        if (closeBtn) closeBtn.addEventListener('click', closePanel);
        const courseCloseBtn = document.getElementById('course-preview-close');
        if (courseCloseBtn) courseCloseBtn.addEventListener('click', closePanel);

        if (window.TravelCannon) {
            window.TravelCannon.mount({ map: svg, selectRegion, closePanel });
        } else {
            const shuffle = document.getElementById('map-shuffle');
            if (shuffle) shuffle.addEventListener('click', pickRandom);
        }

        // 지역 상세의 "지도에서 보기"로 들어오면 해당 지역 카드를 바로 펼친다.
        const initialSigCd = new URLSearchParams(window.location.search).get('sigCd');
        if (initialSigCd && boundsByCode[initialSigCd]) {
            await selectRegion(initialSigCd);
        }

        // 지도 진입 시 현재 위치 수집(부가 기능 · 거부해도 무시)
        if (typeof window.getCurrentPositionSafe === 'function') {
            window.getCurrentPositionSafe();
        }

        await applyDiscovery();
    }

    /* =========================================================
       온보딩 결과를 지도에 얹기
       ========================================================= */

    /** 후보 지역 정보 — 패널을 열 때 '왜 이 지역인지'를 함께 보여주려고 들고 있는다 */
    const discovered = {};

    /**
     * 온보딩을 마쳤으면 후보 지역을 칠하고, 아니면 시작 안내를 띄운다.
     *
     * 후보는 <b>전부 같은 색</b>이다. 농도나 굵기로 순위를 표현하지 않는다 —
     * 그 순간 '랭킹 없는 지도'가 이름만 남는다.
     */
    async function applyDiscovery() {
        const banner = document.getElementById('discovery-banner');
        const cta = document.getElementById('discovery-cta');
        if (!banner || !cta) return;

        // 지도 위 같은 자리를 쓰는 띠들이다. 일반 안내('지도를 눌러 발견해보세요')는
        // 그 사람에게 맞춘 띠가 뜨면 물러나야 한다 — 안 그러면 둘이 겹쳐 둘 다 못 읽는다.
        const hint = document.querySelector('.map-discovery-hint');
        const yieldHint = () => { if (hint) hint.classList.add('hidden'); };

        let data = null;
        try {
            const res = await fetch('/api/onboarding/candidates?limit=12');
            data = await res.json();
        } catch (e) {
            return;   // 실패하면 지도는 평소대로 동작한다
        }

        if (!data || !data.enough) {
            // 발견 패널(xl 이상)이 같은 말을 하고 있으면 이 띠는 뜨지 않는다(CSS 의 xl:!hidden)
            cta.classList.remove('hidden');
            if (getComputedStyle(cta).display !== 'none') yieldHint();
            return;
        }

        const items = data.items || [];
        items.forEach((it) => { discovered[it.sigCd] = it; });

        // 온보딩 직후 한 번만 연출한다. 표식을 바로 지워 새로고침으로 반복되지 않게.
        let reveal = false;
        try {
            reveal = sessionStorage.getItem('discovery.reveal') === '1';
            if (reveal) sessionStorage.removeItem('discovery.reveal');
        } catch (e) { /* 무시 */ }

        const reduced = window.matchMedia
            && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

        if (reveal && !reduced && items.length) {
            revealSequence(data.visited || [], items);
        } else {
            applyNow(data.visited || [], items);
        }

        yieldHint();
        document.getElementById('discovery-headline').textContent =
            data.sentence ? data.sentence + '에 가까운 코스' : '내 여행 결과와 가까운 코스';
        document.getElementById('discovery-sub').textContent =
            '아직 안 가본 곳의 공식 코스 ' + items.length + '개를 지도에 밝혔어요.';
        // 연출 중이면 다 켜진 뒤에 띄운다 — 설명이 먼저 나오면 장면을 가린다
        setTimeout(() => banner.classList.remove('hidden'),
                   revealTimer ? revealTimer + 250 : 0);

        const toggle = document.getElementById('discovery-toggle');
        let on = true;
        toggle.addEventListener('click', () => {
            on = !on;
            items.forEach((it) => {
                const p = svg.querySelector('.sig-path[data-sig-cd="' + it.sigCd + '"]');
                if (p) p.classList.toggle('discovered', on);
            });
            paintGlow(on ? items.map((it) => it.sigCd) : []);
            toggle.textContent = on ? '숨기기' : '다시 보기';
        });
    }

    /** 연출 총 길이(ms) — 배너를 이 뒤에 띄운다 */
    let revealTimer = 0;

    /** 연출 없이 최종 상태로 */
    function applyNow(visited, items) {
        visited.forEach((cd) => {
            const p = svg.querySelector('.sig-path[data-sig-cd="' + cd + '"]');
            if (p) p.classList.add('visited');
        });
        items.forEach((it) => {
            const p = svg.querySelector('.sig-path[data-sig-cd="' + it.sigCd + '"]');
            if (p) p.classList.add('discovered');
        });
        paintGlow(items.map((it) => it.sigCd));
    }

    /** 시군구 폴리곤의 화면상 중심 — 연출 순서를 거리로 정하는 데 쓴다 */
    function centerOf(sigCd) {
        const b = boundsByCode[sigCd];
        if (!b) return null;
        return [(b[0][0] + b[1][0]) / 2, (b[0][1] + b[1][1]) / 2];
    }

    /**
     * 발견의 순간.
     *
     * 내가 고른 곳이 먼저 깜빡이고, 거기서 <b>가까운 순서대로</b> 후보에 불이 들어온다.
     * 순서를 거리로 정하는 건 연출을 위해서가 아니다 —
     * 가나다순으로 켜면 화면 여기저기가 무작위로 튀어서 '퍼져나간다'로 읽히지 않는다.
     *
     * 추천 순위와는 무관하다. 다 켜지고 나면 모두 같은 밝기다.
     */
    function revealSequence(visited, items) {
        const SEED_MS = 760;      // 내가 고른 곳이 충분히 숨을 고른 뒤 코스가 켜진다
        const STEP_MS = 150;      // 빛이 하나씩 터지는 장면을 눈으로 따라갈 수 있는 간격

        svg.classList.add('is-revealing');
        const flash = document.getElementById('map-reveal-flash');
        if (flash) retrigger(flash, 'active');

        visited.forEach((cd) => {
            const p = svg.querySelector('.sig-path[data-sig-cd="' + cd + '"]');
            if (p) { p.classList.add('visited'); p.classList.add('seed-pulse'); }
        });

        const seeds = visited.map(centerOf).filter(Boolean);
        const ordered = items.slice().sort((a, b) => nearest(a.sigCd, seeds) - nearest(b.sigCd, seeds));

        const lit = [];
        ordered.forEach((it, i) => {
            setTimeout(() => {
                const p = svg.querySelector('.sig-path[data-sig-cd="' + it.sigCd + '"]');
                if (p) {
                    p.classList.add('discovered');
                    retrigger(p, 'igniting');
                    setTimeout(() => p.classList.remove('igniting'), 1500);
                }
                burstAt(it.sigCd, i);
                lit.push(it.sigCd);
                paintGlow(lit);          // 켜진 것까지만 빛을 얹는다
            }, SEED_MS + i * STEP_MS);
        });

        revealTimer = SEED_MS + ordered.length * STEP_MS;
        setTimeout(() => {
            svg.querySelectorAll('.sig-path.seed-pulse').forEach((p) => p.classList.remove('seed-pulse'));
            svg.classList.remove('is-revealing');
            if (flash) flash.classList.remove('active');
        }, revealTimer + 400);
    }

    /** 지역 중심에서 작은 불씨가 사방으로 흩어진다. DOM은 애니메이션 뒤 바로 치운다. */
    function burstAt(sigCd, seed) {
        const layer = document.getElementById('map-sparks');
        const center = centerOf(sigCd);
        if (!layer || !center) return;
        const count = 9;
        for (let i = 0; i < count; i++) {
            const angle = ((Math.PI * 2) / count) * i + seed * 0.41;
            const distance = 20 + (i % 3) * 9;
            const spark = document.createElementNS(SVG_NS, 'circle');
            spark.setAttribute('cx', center[0]);
            spark.setAttribute('cy', center[1]);
            spark.setAttribute('r', i % 3 === 0 ? '2.7' : '1.7');
            spark.setAttribute('class', 'map-spark');
            spark.style.setProperty('--spark-x', (Math.cos(angle) * distance).toFixed(2) + 'px');
            spark.style.setProperty('--spark-y', (Math.sin(angle) * distance).toFixed(2) + 'px');
            spark.style.animationDelay = (i * 24) + 'ms';
            layer.appendChild(spark);
            setTimeout(() => spark.remove(), 1300 + i * 24);
        }
    }

    function nearest(sigCd, seeds) {
        const c = centerOf(sigCd);
        if (!c || !seeds.length) return 0;
        let best = Infinity;
        for (const s of seeds) {
            const d = Math.hypot(c[0] - s[0], c[1] - s[1]);
            if (d < best) best = d;
        }
        return best;
    }

    /**
     * 후보 지역의 모양을 복제해 블러 레이어에 얹는다.
     *
     * 면을 색으로 칠하기만 하면 '표시된 지역'으로 읽히는데,
     * 번지는 빛을 깔면 '켜진 지역'으로 읽힌다 — 발견을 장면으로 만드는 장치다.
     * 값(색·번짐·세기)은 tokens.css 의 --glow-* 에서 읽는다.
     */
    function paintGlow(sigCds) {
        const layer = document.getElementById('map-glow');
        if (!layer) return;
        layer.textContent = '';
        if (!sigCds || !sigCds.length) return;

        const css = getComputedStyle(document.documentElement);
        const color = (css.getPropertyValue('--glow-color') || '#FFC661').trim();
        const blur = (css.getPropertyValue('--glow-blur') || '7').trim();
        const opacity = (css.getPropertyValue('--glow-opacity') || '0.85').trim();

        const blurNode = document.getElementById('discovery-glow-blur');
        if (blurNode) blurNode.setAttribute('stdDeviation', blur);
        layer.setAttribute('opacity', opacity);

        sigCds.forEach((cd) => {
            const src = svg.querySelector('#map-base .sig-path[data-sig-cd="' + cd + '"]');
            if (!src) return;
            const clone = document.createElementNS(SVG_NS, 'path');
            clone.setAttribute('d', src.getAttribute('d'));
            clone.setAttribute('fill', color);
            clone.setAttribute('stroke', 'none');
            layer.appendChild(clone);
        });
    }

    /** 선택한 지역만 별도 광원으로 유지한다. 추천 후보의 glow 레이어와 섞이지 않게 분리한다. */
    function paintSelectedGlow(sigCd) {
        const layer = document.getElementById('map-selected-glow');
        if (!layer) return;
        layer.textContent = '';
        const src = svg.querySelector('#map-base .sig-path[data-sig-cd="' + sigCd + '"]');
        if (!src) return;
        const css = getComputedStyle(document.documentElement);
        const color = (css.getPropertyValue('--glow-color') || '#FFC661').trim();
        const blur = (css.getPropertyValue('--glow-blur') || '7').trim();
        const opacity = (css.getPropertyValue('--glow-opacity') || '0.85').trim();
        const blurNode = document.getElementById('discovery-glow-blur');
        if (blurNode) blurNode.setAttribute('stdDeviation', String(Number(blur) + 2));
        layer.setAttribute('opacity', Math.min(1, Number(opacity) + 0.12));
        const clone = document.createElementNS(SVG_NS, 'path');
        clone.setAttribute('d', src.getAttribute('d'));
        clone.setAttribute('fill', color);
        clone.setAttribute('stroke', '#FFF4C8');
        clone.setAttribute('stroke-width', '2');
        layer.appendChild(clone);
    }

    /**
     * 패널 맨 위에 '왜 이 지역인지'와 코스로 가는 입구를 붙인다.
     * 후보로 잡힌 지역에만 나타난다 — 발견 → 근거 → 실행이 한 흐름이 되게.
     */
    function fillDiscoveryNote(sigCd) {
        const old = document.getElementById('panel-discovery');
        if (old) old.remove();

        const it = discovered[sigCd];
        if (!it || !panelBody) return;

        const box = el('div', 'mb-4 p-4 rounded border', null);
        box.id = 'panel-discovery';
        box.style.background = 'var(--accent-soft)';
        box.style.borderColor = 'var(--accent)';

        const label = el('p', 'font-caption text-caption mb-1', '내 여행 결과와 가까운 공식 코스');
        label.style.color = 'var(--accent-hover)';
        box.appendChild(label);

        if (it.courseTitle) {
            box.appendChild(el('h2', 'font-section-title text-card-title text-text-primary mb-1', it.courseTitle));
        }
        if (it.courseSubtitle) {
            box.appendChild(el('p', 'font-caption text-caption text-text-muted mb-2 line-clamp-3', it.courseSubtitle));
        }
        box.appendChild(el('p', 'font-body-main text-body-main text-text-primary', it.reason || ''));

        if (it.matchedTags && it.matchedTags.length) {
            const tags = el('div', 'flex flex-wrap gap-1 mt-2');
            it.matchedTags.forEach((t) => {
                const chip = el('span', 'font-caption text-caption px-2 py-[2px] rounded-full', t);
                chip.style.background = 'var(--surface)';
                chip.style.color = 'var(--accent-hover)';
                tags.appendChild(chip);
            });
            box.appendChild(tags);
        }

        // 코스로 가는 버튼은 넣지 않는다 — 패널 하단에 이미 '이 지역에서 하루 보내기'가 있다.
        // 같은 목적지로 가는 버튼을 두 개 두면 어느 쪽이 주된 행동인지 흐려진다.
        panelBody.prepend(box);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
