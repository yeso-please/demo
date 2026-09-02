// 카카오맵 길찾기 열기 (출발지=현재위치, 목적지=지역좌표)
// - 모바일: 앱 스킴(sp/ep 좌표로 출발·목적지 모두 지정) → 웹 폴백
// - 데스크톱 웹: 카카오 URL만으로는 출발지 좌표 지정이 안 되므로,
//   Kakao JS SDK로 현재 위치를 주소로 역지오코딩해 sName(출발)으로 넣는다.
(function () {
    'use strict';

    let kakaoKey = null;
    let sdkPromise = null;

    function isMobile() {
        return /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
    }
    function hasCoord(o) {
        return o && o.lat != null && o.lng != null && !isNaN(o.lat) && !isNaN(o.lng);
    }

    /* ---------- Kakao SDK 로드(services) ---------- */
    function loadSdk() {
        if (sdkPromise) return sdkPromise;
        sdkPromise = new Promise((resolve) => {
            if (window.kakao && window.kakao.maps && window.kakao.maps.services) { resolve(true); return; }
            if (!kakaoKey) { resolve(false); return; }
            const s = document.createElement('script');
            s.src = '//dapi.kakao.com/v2/maps/sdk.js?appkey=' + kakaoKey + '&libraries=services&autoload=false';
            s.onload = () => {
                if (window.kakao && window.kakao.maps) kakao.maps.load(() => resolve(true));
                else resolve(false);
            };
            s.onerror = () => resolve(false);
            document.head.appendChild(s);
        });
        return sdkPromise;
    }

    /** 좌표 → 주소명 (역지오코딩) */
    function coord2Address(lng, lat) {
        return new Promise((resolve) => {
            if (!(window.kakao && kakao.maps && kakao.maps.services)) { resolve(null); return; }
            const g = new kakao.maps.services.Geocoder();
            g.coord2Address(lng, lat, (result, status) => {
                if (status === kakao.maps.services.Status.OK && result && result[0]) {
                    const a = result[0].road_address || result[0].address;
                    resolve(a ? a.address_name : null);
                } else {
                    resolve(null);
                }
            });
        });
    }

    /* ---------- URL 생성 ---------- */
    function webDestOnly(dest, origin) {
        const name = encodeURIComponent(dest.name || '목적지');
        if (hasCoord(dest)) return 'https://map.kakao.com/link/to/' + name + ',' + dest.lat + ',' + dest.lng;
        if (origin) return 'https://map.kakao.com/?sName=' + encodeURIComponent('현재 위치') + '&eName=' + name;
        return 'https://map.kakao.com/?q=' + name;
    }
    function appScheme(dest, origin) {
        if (!hasCoord(dest)) return null;
        if (origin && hasCoord(origin)) {
            return 'kakaomap://route?sp=' + origin.lat + ',' + origin.lng +
                '&ep=' + dest.lat + ',' + dest.lng + '&by=CAR';
        }
        return 'kakaomap://route?ep=' + dest.lat + ',' + dest.lng + '&by=CAR';
    }

    /**
     * @param dest {lat,lng,name}
     * @param origin {lat,lng}|null
     */
    window.openKakaoRoute = async function (dest, origin) {
        // 모바일: 앱 스킴이 출발·목적지 좌표를 모두 지정
        if (isMobile()) {
            const scheme = appScheme(dest, origin);
            if (scheme) {
                const web = webDestOnly(dest, origin);
                const t = Date.now();
                const timer = setTimeout(() => { if (Date.now() - t < 2000) window.location.href = web; }, 1200);
                window.addEventListener('pagehide', () => clearTimeout(timer), { once: true });
                window.location.href = scheme;
                return;
            }
            window.location.href = webDestOnly(dest, origin);
            return;
        }

        // 데스크톱 웹: 현재위치를 주소로 변환해 출발지까지 지정
        if (origin && hasCoord(origin)) {
            const ready = await loadSdk();
            if (ready) {
                const addr = await coord2Address(origin.lng, origin.lat);
                if (addr) {
                    const url = 'https://map.kakao.com/?sName=' + encodeURIComponent(addr) +
                        '&eName=' + encodeURIComponent(dest.name || '목적지');
                    window.open(url, '_blank', 'noopener');
                    return;
                }
            }
        }
        // 폴백: 목적지만 (현재위치 없음/키 없음/역지오코딩 실패)
        window.open(webDestOnly(dest, origin), '_blank', 'noopener');
    };

    /* ---------- 토스트 ---------- */
    function toast(msg) {
        let el = document.getElementById('geo-toast');
        if (!el) {
            el = document.createElement('div');
            el.id = 'geo-toast';
            el.setAttribute('style',
                'position:fixed;left:50%;bottom:32px;transform:translateX(-50%);background:var(--text);color:var(--bg);' +
                'padding:10px 16px;border-radius:8px;font-size:13px;z-index:9999;opacity:0;transition:opacity .25s ease;max-width:90%;text-align:center;');
            document.body.appendChild(el);
        }
        el.textContent = msg;
        requestAnimationFrame(() => { el.style.opacity = '1'; });
        clearTimeout(el._t);
        el._t = setTimeout(() => { el.style.opacity = '0'; }, 2800);
    }

    /* ---------- [길찾기] 버튼 바인딩 ---------- */
    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('[data-route-btn]').forEach((btn) => {
            const k = btn.getAttribute('data-kakao-key');
            if (k) kakaoKey = k;
            btn.addEventListener('click', async () => {
                const dest = {
                    lat: parseFloat(btn.getAttribute('data-dest-lat')),
                    lng: parseFloat(btn.getAttribute('data-dest-lng')),
                    name: btn.getAttribute('data-dest-name')
                };
                let origin = (window.getCachedGeo && window.getCachedGeo()) || null;
                if (!origin && window.getCurrentPositionSafe) {
                    origin = await window.getCurrentPositionSafe();
                }
                if (!origin) {
                    toast('현재 위치를 가져올 수 없어요. 위치 권한을 허용하면 출발지가 자동으로 채워져요.');
                } else if (!isMobile() && !kakaoKey) {
                    toast('출발지 자동 입력은 카카오 지도 키 설정이 필요해요. 지금은 목적지로 안내해요.');
                }
                window.openKakaoRoute(dest, origin);
            });
        });
        // 키가 있으면 미리 SDK 로드해 클릭 시 지연 최소화
        if (kakaoKey) loadSdk();
    });
})();
