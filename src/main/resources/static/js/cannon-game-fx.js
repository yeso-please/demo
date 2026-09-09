/* Bounded particle pool and one animation clock; no external assets or animation runtime. */
(function () {
    'use strict';
    const NS = 'http://www.w3.org/2000/svg';
    const clamp = value => Math.max(0, Math.min(1, value));
    const mix = (a, b, t) => a + (b - a) * t;
    const colors = ['#FFDB86', '#FFF5D7', '#9AE5CC', '#CFB7FF', '#FFB3A5'];
    let audio;
    function node(tag, attrs, parent) {
        const element = document.createElementNS(NS, tag);
        Object.entries(attrs || {}).forEach(([key, value]) => element.setAttribute(key, value));
        parent?.appendChild(element);
        return element;
    }

    function unlockAudio() {
        try {
            const Audio = window.AudioContext || window.webkitAudioContext;
            if (Audio && !audio) audio = new Audio();
            if (audio?.state === 'suspended') audio.resume().catch(() => {});
        } catch (_) { /* Visual playback never depends on audio availability. */ }
    }

    window.CannonGameFX = {
        unlockAudio,
        play({ asset, ember, dock, map, start, target, passengers, avatar, destination, sound, announce }) {
            const layer = node('svg', { class: 'travel-cannon-flight cannon-game-layer', viewBox: `0 0 ${innerWidth} ${innerHeight}`, 'aria-hidden': 'true' });
            const shade = document.createElement('div');
            shade.className = 'cannon-game-vignette'; shade.setAttribute('aria-hidden', 'true');
            const hud = document.createElement('div');
            hud.className = 'cannon-game-hud'; hud.setAttribute('aria-hidden', 'true');
            hud.innerHTML = '<small>목적지는 운명에 맡기고</small><strong>READY?</strong><div class="cannon-game-meter"><i></i></div><span>여행력 충전 중</span>';
            const title = hud.querySelector('strong'), subtitle = hud.querySelector('span');
            const meter = hud.querySelector('i');
            const boom = document.createElement('div');
            boom.className = 'cannon-game-boom'; boom.textContent = '뻥—!!'; boom.setAttribute('aria-hidden', 'true');
            // Clamp comic lettering independently of a cannon positioned against the screen edge.
            boom.style.left = `${Math.max(90, Math.min(innerWidth - 110, start.x))}px`;
            boom.style.top = `${Math.max(145, Math.min(innerHeight - 120, start.y - 60))}px`;
            document.body.append(shade, layer, hud, boom);

            const charge = node('g', { class: 'cannon-game-charge' }, layer);
            const chargeRings = [0, 1, 2].map(i => node('circle', { cx: start.x, cy: start.y, r: 30, fill: 'none', stroke: i === 1 ? '#FFF5D7' : '#FFD385', 'stroke-width': 1.5, 'stroke-dasharray': i === 1 ? '5 12' : 'none' }, charge));
            const speed = node('g', { opacity: 0 }, layer);
            const center = { x: innerWidth / 2, y: innerHeight * .46 };
            const rays = Array.from({ length: innerWidth < 600 ? 18 : 30 }, (_, i) => {
                const a = i * Math.PI * 2 / (innerWidth < 600 ? 18 : 30);
                return { a, line: node('line', { stroke: '#FFEAC0', 'stroke-width': i % 3 === 0 ? 3 : 1, 'stroke-linecap': 'round' }, speed) };
            });
            const trajectory = node('path', { fill: 'none', stroke: '#F8D28D', 'stroke-width': 2, 'stroke-dasharray': '3 11', opacity: 0 }, layer);
            const blast = node('g', { class: 'travel-cannon-blast', opacity: 0 }, layer);
            node('path', { d: 'M0 25-25 48-29 15-72 14-46-15-76-53-32-42-31-87-4-52 23-72 20-27 55-28 30 0 48 31 15 23Z', fill: '#FFBC58', stroke: '#FFEBAA', 'stroke-width': 2 }, blast);
            node('path', { d: 'M0 10-16 24-19 0-45-25-19-22-18-57 3-31 23-44 15-13 35 4 10 10Z', fill: '#FFF5D1' }, blast);
            const smoke = Array.from({ length: 12 }, (_, i) => node('circle', { r: 0, fill: i % 2 ? '#C4C8D1' : '#FFF0D3', opacity: 0 }, layer));
            // Reuse this particle pool for both launch sparks and landing confetti.
            const particles = Array.from({ length: innerWidth < 600 ? 38 : 64 }, (_, i) => ({
                angle: i * 2.39996, speed: 65 + i % 9 * 17,
                shape: node('rect', { x: -3, y: -5, width: i % 3 ? 5 : 9, height: i % 3 ? 11 : 4, rx: 1, fill: colors[i % colors.length], opacity: 0 }, layer)
            }));
            const impact = node('g', { class: 'cannon-game-impact', opacity: 0 }, layer);
            const impactRings = [0, 1, 2].map(i => node('ellipse', { cx: target.x, cy: target.y, rx: 1, ry: 1, fill: 'none', stroke: i ? '#F8D28D' : '#FFF2C9', 'stroke-width': i ? 2 : 4 }, impact));
            const beacon = node('g', { opacity: 0 }, layer);
            node('path', { d: 'M0-12V-84L40-74 0-61', stroke: '#FFF1C1', 'stroke-width': 3, fill: '#F0BD6C' }, beacon);
            node('circle', { cy: -8, r: 5, fill: '#FFF1C1' }, beacon);

            const flyers = passengers.map((person, i) => {
                const ghosts = Array.from({ length: 3 }, () => {
                    const g = node('g', { opacity: 0 }, layer);
                    g.innerHTML = avatar(person.color);
                    g.firstElementChild.setAttribute('x', '-19'); g.firstElementChild.setAttribute('y', '-28');
                    g.firstElementChild.setAttribute('width', '38'); g.firstElementChild.setAttribute('height', '51');
                    return g;
                });
                const g = node('g', { class: 'cannon-game-flyer', 'data-passenger': i }, layer);
                const inner = node('g', {}, g);
                inner.innerHTML = avatar(person.color);
                const picture = inner.firstElementChild;
                picture.setAttribute('x', '-19'); picture.setAttribute('y', '-28');
                picture.setAttribute('width', '38'); picture.setAttribute('height', '51');
                g.style.opacity = '0';
                return { g, inner, ghosts };
            });

            const notes = new Set();
            function tone(from, to, duration, volume = .06, type = 'sine', delay = 0) {
                if (!sound || !audio || audio.state !== 'running') return;
                try {
                    const oscillator = audio.createOscillator(), gain = audio.createGain();
                    const now = audio.currentTime + delay;
                    oscillator.type = type;
                    oscillator.frequency.setValueAtTime(from, now);
                    oscillator.frequency.exponentialRampToValueAtTime(to, now + duration);
                    gain.gain.setValueAtTime(.001, now);
                    gain.gain.linearRampToValueAtTime(volume, now + .015);
                    gain.gain.exponentialRampToValueAtTime(.001, now + duration);
                    oscillator.connect(gain); gain.connect(audio.destination);
                    oscillator.start(now); oscillator.stop(now + duration + .02);
                    notes.add(oscillator);
                    oscillator.onended = () => { notes.delete(oscillator); oscillator.disconnect(); gain.disconnect(); };
                } catch (_) { /* Audio is optional. */ }
            }
            const chargeMs = 1600, freezeMs = 140, flightMs = 2200, stagger = 95, landingMs = 1100;
            const lastTouchdown = chargeMs + freezeMs + flightMs + (flyers.length - 1) * stagger;
            const total = lastTouchdown + landingMs;
            const originalTranslate = map.style.translate;
            let frame, ended = false, phase = '', lastCount = '', resolveDone;
            const done = new Promise(resolve => { resolveDone = resolve; });
            function setPhase(next) {
                if (phase === next) return;
                phase = next; dock.dataset.fxPhase = next; hud.dataset.phase = next;
                if (next === 'charge') {
                    dock.classList.add('is-lighting'); announce('여행력 충전! 3, 2, 1…');
                } else if (next === 'launch') {
                    dock.classList.remove('is-lighting'); dock.classList.add('is-firing');
                    title.textContent = '발사아아!'; subtitle.textContent = '어디든 간다!';
                    announce('발사! 새로운 여행지로 날아가요.');
                    tone(160, 32, .6, .17, 'triangle'); tone(800, 90, .35, .07, 'sawtooth');
                } else if (next === 'flight') {
                    title.textContent = '우와아아!'; subtitle.textContent = `${passengers.length}명 함께 비행 중`;
                    tone(950, 180, .8, .025, 'sine');
                } else if (next === 'landing') {
                    dock.classList.add('is-landing'); title.textContent = '착지 성공!';
                    subtitle.textContent = destination;
                    tone(140, 40, .25, .12, 'triangle');
                    [523, 659, 784, 1047].forEach((pitch, i) => tone(pitch, pitch, .24, .05, 'sine', .15 + i * .11));
                }
            }

            function position(t, lane = 0) {
                const control = { x: (start.x + target.x) / 2, y: Math.max(42, Math.min(start.y, target.y) - 280) };
                const m = 1 - t;
                // A looping wobble and depth zoom vanish exactly at the selected region.
                return {
                    x: m * m * start.x + 2 * m * t * control.x + t * t * target.x + Math.sin(t * Math.PI * 2) * Math.sin(t * Math.PI) * 55,
                    y: m * m * start.y + 2 * m * t * control.y + t * t * target.y + Math.sin(t * Math.PI * 4) * Math.sin(t * Math.PI) * (16 + lane * 3)
                };
            }
            const route = Array.from({ length: 65 }, (_, i) => { const p = position(i / 64); return `${i ? 'L' : 'M'}${p.x} ${p.y}`; }).join(' ');
            trajectory.setAttribute('d', route);
            function progress(time) {
                const t = clamp(time);
                if (t < .25) return .32 * (1 - Math.pow(1 - t / .25, 2));
                if (t < .65) return .32 + (t - .25) / .4 * .28;
                return .6 + Math.pow((t - .65) / .35, 2) * .4;
            }
            function stop() {
                if (ended) return;
                ended = true; cancelAnimationFrame(frame);
                map.style.translate = originalTranslate;
                asset.style.transform = '';
                dock.classList.remove('is-lighting', 'is-firing', 'is-landing');
                delete dock.dataset.fxPhase;
                layer.remove(); shade.remove(); hud.remove(); boom.remove();
                notes.forEach(note => { try { note.stop(); } catch (_) {} });
                resolveDone();
            }

            const started = performance.now();
            function tick(now) {
                if (ended) return;
                try {
                    const elapsed = now - started;
                    const flightElapsed = elapsed - chargeMs - freezeMs;
                    const launchAge = elapsed - chargeMs;
                    const landingAge = elapsed - lastTouchdown;
                    const fuse = clamp(elapsed / chargeMs), fm = 1 - fuse;
                    if (elapsed < chargeMs) {
                        setPhase('charge');
                        const countdown = elapsed < 350 ? 'READY?' : elapsed < 800 ? '3' : elapsed < 1200 ? '2' : '1';
                        if (countdown !== lastCount) { title.textContent = countdown; lastCount = countdown; tone(400 + fuse * 400, 450 + fuse * 400, .09, .035, 'square'); }
                        meter.style.transform = `scaleX(${fuse})`;
                        ember.setAttribute('transform', `translate(${fm * fm * 205 + 2 * fm * fuse * 201 + fuse * fuse * 187} ${fm * fm * 102 + 2 * fm * fuse * 89 + fuse * fuse * 103}) scale(${1.3 + fuse * .9 + Math.sin(elapsed / 40) * .15})`);
                        asset.style.transform = `translate(${Math.sin(elapsed / 27) * fuse * 3}px,${Math.cos(elapsed / 21) * fuse * 2}px) scale(${1 + fuse * .06},${1 - fuse * .04})`;
                        chargeRings.forEach((ring, i) => {
                            const p = (fuse * 1.8 + i / 3) % 1;
                            ring.setAttribute('r', String(30 + (1 - p) * 90)); ring.setAttribute('opacity', String(p * .55));
                        });
                    } else {
                        if (landingAge >= 0) setPhase('landing');
                        else if (flightElapsed > 350) setPhase('flight');
                        else setPhase('launch');
                        charge.setAttribute('opacity', '0'); asset.style.transform = '';
                    }

                    // One local amber explosion, not repeated whole-screen flashes.
                    const flash = launchAge >= 0 ? Math.max(0, 1 - launchAge / 520) : 0;
                    blast.setAttribute('opacity', String(flash));
                    blast.setAttribute('transform', `translate(${start.x} ${start.y}) scale(${1 + clamp(launchAge / 180) * 2.2})`);
                    boom.style.opacity = String(launchAge >= 0 ? Math.max(0, 1 - launchAge / 850) : 0);
                    boom.style.transform = `translate(-50%, -50%) rotate(-12deg) scale(${.8 + clamp(launchAge / 150) * .5})`;
                    smoke.forEach((puff, i) => {
                        const p = clamp((launchAge - i * 16) / 1300), angle = -Math.PI + .1 + i * .14;
                        puff.setAttribute('cx', String(start.x + Math.cos(angle) * p * (110 + i * 8)));
                        puff.setAttribute('cy', String(start.y + Math.sin(angle) * p * 110 - p * 30));
                        puff.setAttribute('r', String((10 + i % 4 * 4) * (1 + p * 2.4)));
                        puff.setAttribute('opacity', String(Math.sin(p * Math.PI) * .42));
                    });

                    const particleTime = landingAge >= 0 ? landingAge : launchAge;
                    const origin = landingAge >= 0 ? target : start;
                    particles.forEach(({ shape, angle, speed: velocity }, i) => {
                        const p = clamp(particleTime / (landingAge >= 0 ? 1100 : 950));
                        const x = origin.x + Math.cos(angle) * velocity * p;
                        const y = origin.y + Math.sin(angle) * velocity * p + p * p * 150;
                        shape.setAttribute('transform', `translate(${x} ${y}) rotate(${i * 31 + p * 540})`);
                        shape.setAttribute('opacity', String(particleTime >= 0 ? (1 - p) * .95 : 0));
                    });
                    const speedOpacity = flightElapsed >= 0 && landingAge < 0 ? .25 + Math.sin(clamp(flightElapsed / flightMs) * Math.PI) * .2 : 0;
                    speed.setAttribute('opacity', String(speedOpacity));
                    rays.forEach(({ line, a }, i) => {
                        const radius = Math.max(innerWidth, innerHeight) * .48;
                        const inner = radius * (.7 + ((elapsed / 1000 + i * .1) % .3));
                        line.setAttribute('x1', String(center.x + Math.cos(a) * inner)); line.setAttribute('y1', String(center.y + Math.sin(a) * inner));
                        line.setAttribute('x2', String(center.x + Math.cos(a) * radius * 1.3)); line.setAttribute('y2', String(center.y + Math.sin(a) * radius * 1.3));
                    });
                    trajectory.setAttribute('opacity', flightElapsed >= 0 && landingAge < 0 ? '.35' : '0');
                    flyers.forEach(({ g, inner, ghosts }, i) => {
                        const local = flightElapsed - i * stagger;
                        if (local < 0) return;
                        const t = progress(local / flightMs), p = position(t, i);
                        const age = Math.max(0, local - flightMs);
                        const bounce = t === 1 ? -Math.abs(Math.sin(clamp(age / 650) * Math.PI * 2)) * 27 * (1 - clamp(age / 650)) : 0;
                        const scale = .85 + Math.sin(t * Math.PI) * 1.75;
                        g.setAttribute('transform', `translate(${p.x} ${p.y + bounce})`);
                        inner.setAttribute('transform', `rotate(${t * 1440}) scale(${t === 1 ? 1 + Math.sin(age / 45) * .15 * (1 - clamp(age / 400)) : scale},${t === 1 ? 1 - Math.sin(age / 45) * .15 * (1 - clamp(age / 400)) : scale})`);
                        g.style.opacity = t < 1 ? '1' : String(1 - clamp((age - 700) / 350));
                        ghosts.forEach((ghost, j) => {
                            const history = progress((local - (j + 1) * 55) / flightMs), previous = position(history, i);
                            ghost.setAttribute('transform', `translate(${previous.x} ${previous.y}) rotate(${history * 1440}) scale(${.85 + Math.sin(history * Math.PI) * 1.75})`);
                            ghost.setAttribute('opacity', local > 100 && t < 1 ? String(.2 - j * .055) : '0');
                        });
                    });
                    impact.setAttribute('opacity', landingAge >= 0 ? '1' : '0');
                    impactRings.forEach((ring, i) => {
                        const p = clamp((landingAge - i * 110) / 800);
                        ring.setAttribute('rx', String(6 + p * 150)); ring.setAttribute('ry', String(4 + p * 70));
                        ring.setAttribute('opacity', String(landingAge >= i * 110 ? 1 - p : 0));
                    });
                    beacon.setAttribute('opacity', String(clamp(landingAge / 200)));
                    beacon.setAttribute('transform', `translate(${target.x} ${target.y}) scale(${.6 + clamp(landingAge / 260) * .4})`);
                    const shakeAge = landingAge >= 0 ? landingAge : launchAge;
                    const strength = shakeAge >= 0 ? 7 * (1 - clamp(shakeAge / 420)) : 0;
                    map.style.translate = `${Math.sin(elapsed / 17) * strength}px ${Math.cos(elapsed / 13) * strength * .7}px`;
                    if (elapsed >= total) stop(); else frame = requestAnimationFrame(tick);
                } catch (error) { console.error('[cannon-fx]', error); stop(); }
            }
            frame = requestAnimationFrame(tick);
            return { finished: done, skip: stop };
        }
    };
})();
