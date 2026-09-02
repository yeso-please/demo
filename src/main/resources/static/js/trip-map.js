(function () {
    'use strict';
    const SVG_NS = 'http://www.w3.org/2000/svg';

    async function draw(svg) {
        const loading = svg.parentElement.querySelector('#trip-map-loading') || document.getElementById('trip-map-loading');
        try {
            const response = await fetch('/geo/sig.json');
            if (!response.ok) throw new Error('HTTP ' + response.status);
            const topo = await response.json();
            const objectName = Object.keys(topo.objects)[0];
            const features = topojson.feature(topo, topo.objects[objectName]);
            const projection = d3.geoMercator().fitSize([800, 1000], features);
            const path = d3.geoPath(projection);
            const codes = (svg.dataset.candidates || '').split(',').filter(Boolean);
            const directionByCode = {};
            document.querySelectorAll('[data-candidate-card]').forEach(function (card) {
                directionByCode[card.dataset.candidateCard] = card.dataset.direction || '';
            });
            const fragment = document.createDocumentFragment();
            features.features.forEach(function (feature) {
                const d = path(feature);
                if (!d) return;
                const code = feature.properties.SIG_CD;
                const node = document.createElementNS(SVG_NS, 'path');
                node.setAttribute('d', d);
                node.setAttribute('data-sig-cd', code);
                node.setAttribute('class', 'sig-path trip-sig-path');
                const candidateIndex = codes.indexOf(code);
                if (candidateIndex >= 0) {
                    const direction = directionByCode[code] || ['fit', 'expand', 'balance'][candidateIndex] || 'fit';
                    node.classList.add('trip-candidate-region', 'trip-candidate-region--' + direction);
                    node.setAttribute('tabindex', '0');
                    node.setAttribute('role', 'button');
                }
                const title = document.createElementNS(SVG_NS, 'title');
                title.textContent = feature.properties.SIG_KOR_NM;
                node.appendChild(title);
                fragment.appendChild(node);
            });
            svg.appendChild(fragment);
            if (loading) loading.hidden = true;

            svg.addEventListener('click', function (event) {
                const region = event.target.closest('.trip-candidate-region');
                if (!region) return;
                focusCandidate(region.dataset.sigCd);
            });
            document.querySelectorAll('[data-candidate-card]').forEach(function (card) {
                card.addEventListener('click', function (event) {
                    if (event.target.closest('a,button,input,label,textarea')) return;
                    focusCandidate(card.dataset.candidateCard);
                });
            });
        } catch (error) {
            console.error('[trip-map] 지도 로드 실패', error);
            if (loading) loading.textContent = '지도를 불러오지 못했어요.';
        }
    }

    function focusCandidate(code) {
        document.querySelectorAll('[data-candidate-card]').forEach(function (card) {
            card.classList.toggle('is-focused', card.dataset.candidateCard === code);
        });
        document.querySelectorAll('.trip-candidate-region').forEach(function (region) {
            region.classList.toggle('is-focused', region.dataset.sigCd === code);
        });
        const card = document.querySelector('[data-candidate-card="' + code + '"]');
        if (card && window.innerWidth < 900) card.scrollIntoView({behavior: 'smooth', block: 'center'});
    }

    document.querySelectorAll('[data-trip-map]').forEach(draw);
})();
