/* Original SVG artwork + Pointer Events + a screen-space flight layer.
 * Companions here are local session labels, not authenticated trip members. */
(function () {
    'use strict';
    const NS = 'http://www.w3.org/2000/svg';
    const COLORS = ['#F1C477', '#9FD8C5', '#C1B1F0', '#F5AD9C', '#99C9EE', '#DBCB8B', '#E9AAD1', '#B7D99B'];
    const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

    // Fixed, locally authored geometry; user names are always inserted with textContent.
    function avatar(color) {
        return `<svg xmlns="${NS}" viewBox="-24 -30 48 64" aria-hidden="true">
          <path d="M-7 16-10 28M7 16 10 28" stroke="#BCCADB" stroke-width="6" stroke-linecap="round"/>
          <path d="M-9 1-18 10M9 1 18-8" stroke="${color}" stroke-width="6" stroke-linecap="round"/>
          <rect x="-11" y="-3" width="22" height="24" rx="9" fill="${color}"/>
          <path d="m-6-1-17 5 3 5 16-6" fill="#FFF0C9"/>
          <circle cy="-14" r="11" fill="#F5D6B8"/>
          <path d="M-11-15q-2-14 12-13 12 0 11 14L5-18-4-14Z" fill="#344255"/>
          <circle cx="-4" cy="-14" r="1.3" fill="#27354A"/><circle cx="4" cy="-14" r="1.3" fill="#27354A"/>
          <path d="M-3-9q3 3 6 0" stroke="#B76D62" stroke-width="1.5" stroke-linecap="round"/>
        </svg>`;
    }

    function svgNode(tag, attrs = {}) {
        const node = document.createElementNS(NS, tag);
        Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value));
        return node;
    }

    // A bounding-box centre may be outside a concave region or between its islands.
    function landingPoint(map, path) {
        const box = path.getBBox();
        const point = map.createSVGPoint();
        for (const divisions of [1, 8, 24, 64]) {
            for (let y = 0; y < divisions; y++) {
                for (let x = 0; x < divisions; x++) {
                    point.x = box.x + box.width * (x + .5) / divisions;
                    point.y = box.y + box.height * (y + .5) / divisions;
                    if (path.isPointInFill(point)) return point.matrixTransform(path.getScreenCTM());
                }
            }
        }
        throw new Error('이 지역의 착지 위치를 찾지 못했어요. 다시 시도해 주세요.');
    }

    window.TravelCannon = {
        mount({ map, selectRegion, closePanel }) {
            const dock = document.getElementById('travel-cannon');
            if (!dock || dock.dataset.mounted) return;
            dock.dataset.mounted = 'true';
            dock.innerHTML = `
              <div class="travel-cannon__heading"><span class="travel-cannon__count"></span></div>
              <div class="travel-cannon__crew" role="group" aria-label="동행 아바타 선택"></div>
              <details class="travel-cannon__add"><summary>동행 아바타 추가</summary>
                <form><label for="cannon-companion-name">함께 갈 사람 이름</label>
                  <div><input id="cannon-companion-name" maxlength="12" autocomplete="off" placeholder="이름 또는 별명" required/><button type="submit">추가</button></div>
                  <p>이번 화면에서 사용할 아바타예요. 친구 계정으로 초대되지는 않아요.</p>
                </form>
              </details>
              <div class="travel-cannon__scene">
                <button type="button" id="map-shuffle" class="travel-cannon__asset" aria-label="대포에 불을 붙여 여행 발사하기" aria-describedby="cannon-instruction">
                  <img src="/images/travel-cannon.svg" width="240" height="180" alt="" draggable="false"/>
                  <svg class="travel-cannon__fuse" viewBox="0 0 240 180" aria-hidden="true">
                    <g class="travel-cannon__ember">
                      <circle r="10" fill="#FF9C36" opacity=".22"/>
                      <path d="M0 5C-8 0-3-5 0-13C2-6 9-4 4 2Z" fill="#FFAA32"/>
                      <path d="M0 4Q-4 0 1-6Q6 0 0 4Z" fill="#FFF4B9"/>
                      <path d="M-6-8-9-12M7-5 11-8M-7 2-12 4" stroke="#FFDA80" stroke-width="1.5" stroke-linecap="round"/>
                    </g>
                  </svg>
                  <span class="travel-cannon__drop">여기에 쏙!</span>
                </button>
                <span class="travel-cannon__loaded" aria-hidden="true"></span>
              </div>
              <p id="cannon-instruction" class="travel-cannon__instruction">끌어서 대포 이동 · 짧게 누르면 발사 · 방향키로도 이동</p>
              <button type="button" class="travel-cannon__reset" hidden>대포 원위치</button>
              <button type="button" class="travel-cannon__sound" aria-pressed="false">효과음 꺼짐</button>
              <button type="button" class="travel-cannon__skip" hidden>애니메이션 건너뛰기</button>
              <p class="travel-cannon__status" role="status" aria-live="polite" aria-atomic="true">나는 탑승 완료. 함께 갈 사람도 태워 볼까요?</p>`;

            const crewBox = dock.querySelector('.travel-cannon__crew');
            const asset = dock.querySelector('.travel-cannon__asset');
            const launch = asset;
            const ember = dock.querySelector('.travel-cannon__ember');
            const skip = dock.querySelector('.travel-cannon__skip');
            const status = dock.querySelector('.travel-cannon__status');
            const form = dock.querySelector('form');
            const input = form.querySelector('input');
            const scene = dock.querySelector('.travel-cannon__scene');
            const resetPosition = dock.querySelector('.travel-cannon__reset');
            const sound = dock.querySelector('.travel-cannon__sound');
            const crew = [{ id: 0, name: '나', color: COLORS[0], aboard: true }];
            let busy = false;
            let nextId = 1;
            let finishFlight = null;
            let drag = null;
            let suppressClick = false;
            let requestController = null;
            let cannonDrag = null;
            let suppressLaunchClick = false;
            let cannonOffset = { x: 0, y: 0 };
            let soundEnabled = false;

            // Move the scene, not the recoil-animated button. Flight always measures its actual muzzle.
            function moveCannon(x, y) {
                const area = dock.parentElement.getBoundingClientRect();
                const box = asset.getBoundingClientRect();
                const baseX = box.left - cannonOffset.x;
                const baseY = box.top - cannonOffset.y;
                cannonOffset.x = Math.max(area.left + 8 - baseX, Math.min(area.right - box.width - 8 - baseX, x));
                cannonOffset.y = Math.max(area.top + 8 - baseY, Math.min(area.bottom - box.height - 8 - baseY, y));
                scene.style.translate = `${cannonOffset.x}px ${cannonOffset.y}px`;
                resetPosition.hidden = Math.abs(cannonOffset.x) + Math.abs(cannonOffset.y) < 2;
            }

            function endCannonDrag(cancelled = false) {
                if (!cannonDrag) return;
                const previous = cannonDrag;
                cannonDrag = null;
                scene.classList.remove('is-moving');
                if (asset.hasPointerCapture(previous.id)) asset.releasePointerCapture(previous.id);
                if (previous.moved) {
                    suppressLaunchClick = true;
                    if (cancelled) moveCannon(previous.offset.x, previous.offset.y);
                    announce(cancelled ? '대포 이동을 취소했어요.' : '여기서 발사! 대포를 짧게 눌러 주세요.');
                }
            }

            asset.addEventListener('pointerdown', event => {
                if (busy || drag || cannonDrag || event.button !== 0) return;
                suppressLaunchClick = false;
                cannonDrag = { id: event.pointerId, x: event.clientX, y: event.clientY, offset: { ...cannonOffset }, moved: false };
                asset.setPointerCapture(event.pointerId);
            });
            asset.addEventListener('pointermove', event => {
                if (!cannonDrag || cannonDrag.id !== event.pointerId) return;
                const dx = event.clientX - cannonDrag.x, dy = event.clientY - cannonDrag.y;
                if (!cannonDrag.moved && Math.hypot(dx, dy) < 9) return;
                cannonDrag.moved = true;
                scene.classList.add('is-moving');
                moveCannon(cannonDrag.offset.x + dx, cannonDrag.offset.y + dy);
            });
            asset.addEventListener('pointerup', event => { if (cannonDrag?.id === event.pointerId) endCannonDrag(); });
            asset.addEventListener('pointercancel', () => endCannonDrag(true));
            asset.addEventListener('lostpointercapture', () => endCannonDrag(true));
            asset.addEventListener('keydown', event => {
                if (busy) return;
                const step = event.shiftKey ? 40 : 12;
                const directions = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
                if (directions[event.key]) {
                    event.preventDefault();
                    const [x, y] = directions[event.key];
                    moveCannon(cannonOffset.x + x, cannonOffset.y + y);
                } else if (event.key === 'Home') { event.preventDefault(); moveCannon(0, 0); }
            });
            resetPosition.addEventListener('click', () => { if (!busy) { moveCannon(0, 0); asset.focus(); } });
            sound.addEventListener('click', () => {
                soundEnabled = !soundEnabled;
                sound.setAttribute('aria-pressed', String(soundEnabled));
                sound.textContent = soundEnabled ? '효과음 켜짐' : '효과음 꺼짐';
                if (soundEnabled) window.CannonGameFX?.unlockAudio();
            });

            function announce(message, error = false) {
                status.textContent = message;
                status.classList.toggle('is-error', error);
            }

            function renderCrew() {
                const focused = document.activeElement?.dataset?.crewId;
                crewBox.replaceChildren();
                crew.forEach(person => {
                    const button = document.createElement('button');
                    button.type = 'button';
                    button.className = 'travel-cannon__person';
                    button.dataset.crewId = String(person.id);
                    button.setAttribute('aria-pressed', String(person.aboard));
                    button.setAttribute('aria-label', `${person.name}, ${person.aboard ? '탑승 중. 누르면 내리기' : '누르거나 대포로 끌어서 탑승'}`);
                    button.innerHTML = avatar(person.color) + '<span></span><i aria-hidden="true"></i>';
                    button.querySelector('span').textContent = person.name;
                    crewBox.appendChild(button);
                });
                const total = crew.filter(person => person.aboard).length;
                dock.querySelector('.travel-cannon__count').textContent = `${total}명 탑승`;
                dock.querySelector('.travel-cannon__loaded').textContent = total ? `${total}명 준비 완료` : '누가 먼저 탈까요?';
                asset.classList.toggle('has-passengers', total > 0);
                if (focused != null) crewBox.querySelector(`[data-crew-id="${focused}"]`)?.focus();
            }

            function board(person, value) {
                person.aboard = value;
                renderCrew();
                announce(`${person.name}${value ? ', 탑승 완료!' : ', 대포에서 내렸어요.'}`);
                if (value && !reduced()) {
                    asset.animate([{ transform: 'scale(1)' }, { transform: 'scale(1.06,.95)' }, { transform: 'scale(1)' }], { duration: 300 });
                }
            }

            form.addEventListener('submit', event => {
                event.preventDefault();
                if (busy) return;
                const name = input.value.trim();
                if (!name) { input.focus(); return; }
                if (crew.length >= 8) { announce('아바타는 나를 포함해 8명까지 추가할 수 있어요.', true); return; }
                if (crew.some(person => person.name === name)) { announce('이미 있는 이름이에요. 다른 별명으로 구분해 주세요.', true); return; }
                crew.push({ id: nextId++, name, color: COLORS[crew.length], aboard: false });
                renderCrew();
                input.value = '';
                dock.querySelector('details').open = false;
                crewBox.lastElementChild.focus();
                announce(`${name} 아바타를 대포로 끌어 넣어 보세요.`);
            });

            crewBox.addEventListener('click', event => {
                if (busy || suppressClick) return;
                const button = event.target.closest('[data-crew-id]');
                if (!button) return;
                const person = crew.find(item => String(item.id) === button.dataset.crewId);
                board(person, !person.aboard);
            });

            function endDrag(cancelled = false) {
                if (!drag) return;
                const current = drag;
                drag = null;
                current.ghost?.remove();
                current.button.classList.remove('is-dragging');
                asset.classList.remove('is-drop-target');
                if (current.button.hasPointerCapture(current.pointerId)) current.button.releasePointerCapture(current.pointerId);
                if (current.moved) {
                    suppressClick = true;
                    setTimeout(() => { suppressClick = false; }, 0);
                    if (!cancelled && current.over) board(current.person, true);
                    else announce('대포 위에 놓으면 탑승해요. 눌러서 태워도 돼요.');
                }
            }

            crewBox.addEventListener('pointerdown', event => {
                if (busy || drag || cannonDrag || event.button !== 0) return;
                const button = event.target.closest('[data-crew-id]');
                if (!button) return;
                drag = { button, person: crew.find(item => String(item.id) === button.dataset.crewId), pointerId: event.pointerId, x: event.clientX, y: event.clientY, moved: false, over: false };
                button.setPointerCapture(event.pointerId);
            });
            crewBox.addEventListener('pointermove', event => {
                if (!drag || drag.pointerId !== event.pointerId) return;
                if (!drag.moved && Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 7) return;
                if (!drag.moved) {
                    drag.moved = true;
                    drag.ghost = document.createElement('div');
                    drag.ghost.className = 'travel-cannon-ghost';
                    drag.ghost.innerHTML = avatar(drag.person.color);
                    document.body.appendChild(drag.ghost);
                    drag.button.classList.add('is-dragging');
                }
                drag.ghost.style.left = `${event.clientX}px`;
                drag.ghost.style.top = `${event.clientY}px`;
                const box = asset.getBoundingClientRect();
                drag.over = event.clientX >= box.left && event.clientX <= box.right && event.clientY >= box.top && event.clientY <= box.bottom;
                asset.classList.toggle('is-drop-target', drag.over);
            });
            crewBox.addEventListener('pointerup', event => { if (drag?.pointerId === event.pointerId) endDrag(); });
            crewBox.addEventListener('pointercancel', () => endDrag(true));
            crewBox.addEventListener('lostpointercapture', () => endDrag(true));
            document.addEventListener('keydown', event => {
                if (event.key === 'Escape') { endDrag(true); endCannonDrag(true); finishFlight?.(); }
            });

            function setBusy(value) {
                busy = value;
                dock.setAttribute('aria-busy', String(value));
                dock.querySelectorAll('button, input').forEach(node => { if (node !== skip) node.disabled = value; });
                launch.setAttribute('aria-label', value ? '여행 발사 준비 중' : '대포에 불을 붙여 여행 발사하기');
            }

            async function fly(path, passengers) {
                skip.hidden = false;
                const target = landingPoint(map, path);
                // Cancel any boarding squash before taking the actual muzzle measurement.
                asset.getAnimations().forEach(animation => animation.cancel());
                const imageBox = asset.querySelector('img').getBoundingClientRect();
                const start = { x: imageBox.left + imageBox.width * 57 / 240, y: imageBox.top + imageBox.height * 57 / 180 };
                const playback = window.CannonGameFX.play({
                    asset, ember, dock, map, start, target, passengers, avatar,
                    destination: path.dataset.name || '새로운 여행지', sound: soundEnabled, announce
                });
                finishFlight = playback.skip;
                try { await playback.finished; }
                finally { finishFlight = null; skip.hidden = true; }
            }

            async function fire() {
                if (busy) return;
                const passengers = crew.filter(person => person.aboard);
                if (!passengers.length) { announce('먼저 아바타를 한 명 이상 태워 주세요.', true); crewBox.firstElementChild.focus(); return; }
                if (!map.querySelector('.sig-path')) { announce('지도가 준비되면 발사할 수 있어요.', true); return; }
                setBusy(true);
                announce('어느 지역에 도착할까요?');
                const controller = new AbortController();
                requestController = controller;
                const timeout = setTimeout(() => controller.abort(), 8000);
                try {
                    const response = await fetch('/api/recommend', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ styles: [], mood: '', freeText: '' }), signal: controller.signal });
                    if (!response.ok) throw new Error('목적지를 뽑지 못했어요. 다시 발사해 주세요.');
                    const result = await response.json();
                    const path = Array.from(map.querySelectorAll('.sig-path')).find(node => node.dataset.sigCd === String(result?.sigCd));
                    if (!path) throw new Error('지금 여행할 수 있는 지역을 찾지 못했어요. 잠시 후 다시 시도해 주세요.');
                    clearTimeout(timeout);
                    closePanel();
                    if (!reduced()) {
                        await pause(480); // Let the previous detail panel finish closing before measuring.
                        await fly(path, passengers);
                    }
                    const opened = await selectRegion(String(result.sigCd));
                    const destination = result.name || path.dataset.name;
                    announce(`${passengers.map(person => person.name).join(', ')} — ${destination} 도착!${opened === false ? ' 지역 정보를 불러오지 못했어요. 지도에서 지역을 다시 눌러 주세요.' : ' 이 지역으로 여행을 떠나 볼까요?'}`, opened === false);
                } catch (error) {
                    announce(error.name === 'AbortError' ? '응답이 늦어지고 있어요. 다시 발사해 주세요.' : error.message, true);
                } finally {
                    clearTimeout(timeout);
                    requestController = null;
                    skip.hidden = true;
                    setBusy(false);
                    if (document.activeElement === skip || document.activeElement === document.body) launch.focus({ preventScroll: true });
                }
            }

            skip.addEventListener('click', () => finishFlight?.());
            // Keep the map still while measuring/flying; the dock's skip control stays usable.
            dock.parentElement.addEventListener('click', event => {
                if (busy && !dock.contains(event.target)) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                }
            }, true);
            map.addEventListener('keydown', event => {
                if (busy && (event.key === 'Enter' || event.key === ' ')) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                }
            }, true);
            window.addEventListener('resize', () => { endDrag(true); endCannonDrag(true); finishFlight?.(); moveCannon(0, 0); });
            document.addEventListener('visibilitychange', () => { if (document.hidden) { endDrag(true); endCannonDrag(true); finishFlight?.(); } });
            window.addEventListener('pagehide', () => { endDrag(true); endCannonDrag(true); requestController?.abort(); finishFlight?.(); });
            launch.addEventListener('click', event => {
                if (suppressLaunchClick && event.detail !== 0) { suppressLaunchClick = false; return; }
                if (soundEnabled) window.CannonGameFX?.unlockAudio();
                fire();
            });
            renderCrew();
        }
    };
})();
