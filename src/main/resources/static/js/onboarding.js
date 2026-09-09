// 여행 MBTI 검사 — 한 번에 한 문항, 고르면 다음으로.
// 12문항이 끝나면 경험 태그·제외 조건을 한 화면에서 더 받고 결과를 낸다.
// 문항과 채점은 서버(TravelMbtiService)가 단일 출처다 — 여기서는 고른 값만 모아 보낸다.
(function () {
    'use strict';

    const MAX_TAGS = 5;

    let intro, quiz, tagStep, result, questions, bar, current, backBtn;
    let tagButtons = [], exButtons = [], tagCount;
    let answers = [];   // 문항 순서대로 1 또는 2
    let index = 0;

    /** 섹션은 flex 레이아웃이라 hidden 을 뗄 때 flex 를 되살려야 한다 */
    function show(el, on) {
        if (!el) return;
        el.classList.toggle('hidden', !on);
        el.classList.toggle('flex', on);
    }

    function renderStep() {
        questions.forEach((q, i) => {
            q.classList.toggle('hidden', i !== index);
            q.classList.toggle('flex', i === index);
        });
        current.textContent = index + 1;
        // 태그 단계까지 포함해 진행률을 센다 — 12문항이 끝나도 아직 한 걸음 남아 있다
        bar.style.width = Math.round(((index + 1) / (questions.length + 1)) * 100) + '%';
        backBtn.style.visibility = index === 0 ? 'hidden' : '';
        // 이전으로 돌아왔을 때 이미 고른 답이 있으면 그대로 보여준다
        questions[index].querySelectorAll('.mbti-a').forEach((b) => {
            b.classList.toggle('picked', answers[index] === Number(b.getAttribute('data-pick')));
        });
    }

    function pick(qEl, value) {
        qEl.querySelectorAll('.mbti-a').forEach((b) => {
            b.classList.toggle('picked', Number(b.getAttribute('data-pick')) === value);
        });
        answers[index] = value;

        // 고른 티가 나도록 잠깐 두었다가 넘어간다
        setTimeout(() => {
            if (index < questions.length - 1) {
                index++;
                renderStep();
            } else {
                showTagStep();
            }
        }, 220);
    }

    /* ===== 경험 태그 · 제외 조건 ===== */

    function pickedTags() {
        return tagButtons.filter((b) => b.classList.contains('picked'))
                         .map((b) => b.getAttribute('data-tag'));
    }

    function pickedExcludes() {
        return exButtons.filter((b) => b.classList.contains('picked'))
                        .map((b) => b.getAttribute('data-ex'));
    }

    /** 5개를 채우면 나머지를 흐리게 — 눌러보기 전에 상한을 알 수 있게 한다 */
    function renderTagState() {
        const n = pickedTags().length;
        if (tagCount) tagCount.textContent = n;
        tagButtons.forEach((b) => {
            b.classList.toggle('full', n >= MAX_TAGS && !b.classList.contains('picked'));
        });
    }

    function toggleTag(btn) {
        if (!btn.classList.contains('picked') && pickedTags().length >= MAX_TAGS) return;
        btn.classList.toggle('picked');
        renderTagState();
    }

    function showTagStep() {
        show(quiz, false);
        show(tagStep, true);
        bar.style.width = '100%';
        renderTagState();
    }

    async function submit() {
        try {
            const res = await fetch('/api/mbti/result', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    answers: answers,
                    tags: pickedTags(),
                    excludes: pickedExcludes(),
                }),
            });
            const d = await res.json();
            if (!res.ok || d.error) {
                showError(d && d.error);
                return;
            }
            document.getElementById('mbti-emoji').textContent = d.emoji || '🧳';
            document.getElementById('mbti-code').textContent = d.code || '';
            document.getElementById('mbti-label').textContent = d.label || '';
            document.getElementById('mbti-tagline').textContent = d.tagline || '';
            document.getElementById('mbti-style').textContent = d.style || '';
            renderResultTags(d.tags, d.excludes);

            // 저장되지 않았으면(비로그인) 가입 안내를 띄운다
            const note = document.getElementById('mbti-signup-note');
            if (note) note.classList.toggle('hidden', !!d.saved);

            show(tagStep, false);
            show(quiz, false);
            show(result, true);
        } catch (e) {
            showError();
        }
    }

    /** 서버가 정규화해 돌려준 값만 그린다 — 사전에 없는 태그는 여기까지 오지 않는다 */
    function renderResultTags(tags, excludes) {
        const box = document.getElementById('mbti-result-tags');
        if (!box) return;
        box.textContent = '';
        (tags || []).forEach((t) => {
            const el = document.createElement('span');
            el.className = 'result-tag';
            el.textContent = t;
            box.appendChild(el);
        });
        (excludes || []).forEach((t) => {
            const el = document.createElement('span');
            el.className = 'result-tag result-tag--exclude';
            el.textContent = '빼기 · ' + t;
            box.appendChild(el);
        });
        if (!box.children.length) {
            const el = document.createElement('span');
            el.className = 'result-tag result-tag--exclude';
            el.textContent = '고른 태그 없음 — 균형형으로 코스를 만들어요';
            box.appendChild(el);
        }
    }

    /** 결과를 못 받았을 때도 화면이 멈추지 않게 안내를 띄운다 */
    function showError(message) {
        document.getElementById('mbti-emoji').textContent = '🧭';
        document.getElementById('mbti-code').textContent = '';
        document.getElementById('mbti-label').textContent = '결과를 가져오지 못했어요';
        document.getElementById('mbti-tagline').textContent = message || '잠시 후 다시 시도해 주세요.';
        document.getElementById('mbti-style').textContent = '';
        const note = document.getElementById('mbti-signup-note');
        if (note) note.classList.add('hidden');
        show(quiz, false);
        show(tagStep, false);
        show(result, true);
    }

    function start() {
        answers = [];
        index = 0;
        show(intro, false);
        show(result, false);
        show(tagStep, false);
        show(quiz, true);
        renderStep();
    }

    function init() {
        intro = document.getElementById('mbti-intro');
        quiz = document.getElementById('mbti-quiz');
        tagStep = document.getElementById('mbti-tags');
        result = document.getElementById('mbti-result');
        bar = document.getElementById('mbti-bar');
        current = document.getElementById('mbti-current');
        backBtn = document.getElementById('mbti-back');
        if (!intro || !quiz || !result) return;

        questions = Array.from(quiz.querySelectorAll('.mbti-q'));
        if (!questions.length) return;

        tagButtons = Array.from(document.querySelectorAll('.tag-btn'));
        exButtons = Array.from(document.querySelectorAll('.ex-btn'));
        tagCount = document.getElementById('mbti-tag-count');

        document.getElementById('mbti-start').addEventListener('click', start);
        document.getElementById('mbti-retry').addEventListener('click', start);

        backBtn.addEventListener('click', () => {
            if (index > 0) {
                index--;
                renderStep();
            }
        });

        quiz.addEventListener('click', (e) => {
            const btn = e.target.closest('.mbti-a');
            if (!btn) return;
            const qEl = btn.closest('.mbti-q');
            if (!qEl || qEl.classList.contains('hidden')) return;
            pick(qEl, Number(btn.getAttribute('data-pick')));
        });

        tagButtons.forEach((b) => b.addEventListener('click', () => toggleTag(b)));
        exButtons.forEach((b) => b.addEventListener('click', () => b.classList.toggle('picked')));
        renderTagState();

        const tagsBack = document.getElementById('mbti-tags-back');
        if (tagsBack) {
            tagsBack.addEventListener('click', () => {
                show(tagStep, false);
                show(quiz, true);
                renderStep();
            });
        }
        const tagsNext = document.getElementById('mbti-tags-next');
        if (tagsNext) tagsNext.addEventListener('click', submit);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
