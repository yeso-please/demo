(function () {
    'use strict';

    const people = document.getElementById('people');
    const output = document.getElementById('people-output');
    function setPeople(next) {
        if (!people || !output) return;
        const value = Math.max(1, Math.min(10, next));
        people.value = String(value);
        output.textContent = String(value);
    }
    document.querySelector('[data-people-minus]')?.addEventListener('click', () => setPeople(Number(people.value) - 1));
    document.querySelector('[data-people-plus]')?.addEventListener('click', () => setPeople(Number(people.value) + 1));

    document.querySelectorAll('.evidence-toggle').forEach(function (button) {
        button.addEventListener('click', function () {
            const evidence = button.closest('.candidate-body').querySelector('.candidate-evidence');
            const open = evidence.hidden;
            evidence.hidden = !open;
            button.setAttribute('aria-expanded', String(open));
        });
    });

    const dialog = document.getElementById('share-dialog');
    document.querySelector('[data-open-share]')?.addEventListener('click', () => dialog?.showModal());
    document.querySelector('[data-close-share]')?.addEventListener('click', () => dialog?.close());
    dialog?.addEventListener('click', function (event) { if (event.target === dialog) dialog.close(); });

    const nickname = document.getElementById('room-nickname');
    if (nickname) {
        const storageKey = 'sumeun-trip-nickname';
        nickname.value = localStorage.getItem(storageKey) || '';
        nickname.addEventListener('input', () => localStorage.setItem(storageKey, nickname.value.trim()));
        document.querySelectorAll('.reaction-form').forEach(function (form) {
            form.addEventListener('submit', function (event) {
                const value = nickname.value.trim();
                if (!value) {
                    event.preventDefault();
                    nickname.focus();
                    nickname.setCustomValidity('일행이 알아볼 수 있는 이름을 입력해 주세요.');
                    nickname.reportValidity();
                    return;
                }
                nickname.setCustomValidity('');
                form.querySelector('.reaction-nickname').value = value;
            });
        });
    }

    document.querySelectorAll('[data-copy-link]').forEach(function (button) {
        button.addEventListener('click', async function () {
            try {
                await navigator.clipboard.writeText(window.location.href.replace(/\/summary$/, ''));
                const original = button.innerHTML;
                button.innerHTML = '<span class="material-symbols-outlined">check</span> 복사됨';
                setTimeout(() => { button.innerHTML = original; }, 1400);
            } catch (_) {
                window.prompt('공유 링크를 복사하세요.', window.location.href.replace(/\/summary$/, ''));
            }
        });
    });

    document.querySelector('[data-scroll-next]')?.addEventListener('click', function () {
        document.querySelector('.shared-candidate-column')?.scrollIntoView({behavior: 'smooth'});
    });
})();
