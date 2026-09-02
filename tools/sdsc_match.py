#!/usr/bin/env python3
"""
T맵 연관관광지 POI 에 좌표를 붙일 수 있는지 검증한다.

배경:
  T맵 유니버스(약 41,000 POI)를 마스터로 쓰기로 했는데, T맵 응답에는 좌표가 없다.
  좌표가 없으면 지도 표시 · 코스 조립 · spatial view 가 전부 막힌다.
  소진공 상가(상권)정보에는 상호명 + 경위도 + 3단계 업종이 있으므로 이걸로 메울 수 있는지 본다.

측정:
  표본 시군구에서 T맵 POI 이름이
    (1) 우리 DB(관광지/음식점)      로 커버되는 비율
    (2) 상가정보                    로 커버되는 비율
    (3) 둘 중 하나라도              로 커버되는 비율   <- 실제 좌표 획득률
  을 카테고리별로 낸다.

사용법:
  python tools/sdsc_match.py                    # 기본 표본 6곳
  python tools/sdsc_match.py --sigungu 51150 11110
"""

from __future__ import annotations

import argparse
import collections
import csv
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except Exception:
        pass

ROOT = Path(__file__).resolve().parent.parent
SECRET = ROOT / "config" / "application-secret.yaml"
OUR_POIS = ROOT / "data" / "rlte" / "our_pois.csv"

RLTE_URL = "http://apis.data.go.kr/B551011/TarRlteTarService1/areaBasedList1"
SDSC_URL = "http://apis.data.go.kr/B553077/api/open/sdsc2/storeListInDong"

# (API 신코드, 우리 DB 구코드, 이름)
SAMPLES = [
    ("51150", "42150", "강릉시"),
    ("11110", "11110", "서울 종로구"),
    ("50110", "50110", "제주시"),
    ("47170", "47170", "안동시"),
    ("26110", "26110", "부산 중구"),
    ("46170", "46170", "전남 여수시"),
]
BASE_YM = "202504"
REV = {"51": "42", "52": "45"}


def load_key() -> str:
    text = SECRET.read_text(encoding="utf-8")
    m = re.search(r"tour:\s*\n\s*api:\s*\n\s*key:\s*(.+)", text)
    if not m:
        sys.exit("인증키를 찾지 못했습니다.")
    return m.group(1).strip().strip('"').strip("'")


def norm(s: str | None) -> str:
    s = (s or "").strip()
    s = re.split(r"[/(\[]", s)[0]
    return re.sub(r"\s+", "", s).lower()


def get_json(url: str, params: dict, key: str, timeout: int = 25):
    q = urllib.parse.urlencode(params, encoding="utf-8")
    full = f"{url}?serviceKey={urllib.parse.quote(key, safe='')}&{q}"
    req = urllib.request.Request(full, headers={"User-Agent": "sumeun-sdsc-match/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"
    if not body.lstrip().startswith("{"):
        return None, " ".join(body.split())[:200]
    try:
        return json.loads(body), None
    except json.JSONDecodeError as e:
        return None, str(e)


def fetch_rlte(key: str, signgu_cd: str) -> list[dict]:
    """한 시군구의 T맵 연관관광지 전량."""
    out, page = [], 1
    while True:
        d, err = get_json(RLTE_URL, {
            "MobileOS": "ETC", "MobileApp": "SumeunMatch", "_type": "json",
            "numOfRows": "1000", "pageNo": str(page),
            "baseYm": BASE_YM, "areaCd": signgu_cd[:2], "signguCd": signgu_cd,
        }, key)
        if err:
            print(f"    T맵 오류: {err}")
            break
        body = (d.get("response", {}) or {}).get("body", {}) or {}
        items = body.get("items")
        rows = [] if not items else (items.get("item") if isinstance(items, dict) else items)
        if isinstance(rows, dict):
            rows = [rows]
        if not rows:
            break
        out.extend(rows)
        if len(out) >= int(body.get("totalCount") or 0) or len(rows) < 1000:
            break
        page += 1
        time.sleep(0.1)
    return out


def fetch_sdsc(key: str, signgu_cd: str) -> list[dict]:
    """한 시군구의 상가업소 전량."""
    out, page = [], 1
    while True:
        d, err = get_json(SDSC_URL, {
            "divId": "signguCd", "key": signgu_cd,
            "numOfRows": "1000", "pageNo": str(page), "type": "json",
        }, key)
        if err:
            print(f"    상가정보 오류: {err}")
            break
        body = d.get("body", {}) or {}
        rows = body.get("items") or []
        if isinstance(rows, dict):
            rows = [rows]
        if not rows:
            break
        out.extend(rows)
        total = int(body.get("totalCount") or 0)
        if len(out) >= total or len(rows) < 1000:
            break
        page += 1
        time.sleep(0.1)
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description="T맵 POI 좌표 획득률 검증")
    ap.add_argument("--sigungu", nargs="*", help="API 신코드 목록 (기본: 표본 6곳)")
    args = ap.parse_args()

    key = load_key()

    # 우리 DB 이름 (구 코드 기준)
    ours = collections.defaultdict(set)
    if OUR_POIS.exists():
        for r in csv.DictReader(OUR_POIS.open(encoding="utf-8")):
            n = norm(r["NAME"])
            if n:
                ours[r["SIG_CD"]].add(n)
    else:
        print(f"경고: {OUR_POIS} 없음 — 우리 DB 커버리지는 0으로 계산됩니다\n")

    samples = SAMPLES
    if args.sigungu:
        samples = [(s, REV.get(s[:2], s[:2]) + s[2:], s) for s in args.sigungu]

    grand = collections.Counter()
    grand_cat = collections.defaultdict(collections.Counter)

    for api_cd, our_cd, name in samples:
        print(f"=== {name} ({api_cd}) ===")
        rlte = fetch_rlte(key, api_cd)
        sdsc = fetch_sdsc(key, api_cd)
        print(f"  T맵 행 {len(rlte):,} · 상가업소 {len(sdsc):,}")
        if not rlte:
            print()
            continue

        store_names = {norm(s.get("bizesNm")) for s in sdsc}
        store_names.discard("")
        with_coord = {norm(s.get("bizesNm")) for s in sdsc if s.get("lat") and s.get("lon")}
        with_coord.discard("")
        our_names = ours.get(our_cd, set())

        # 이 시군구에 속한 T맵 POI 고유 집합 (seed + 연관, 자기 시군구 것만)
        pois: dict[str, str] = {}     # norm name -> category
        for r in rlte:
            hn = norm(r.get("tAtsNm"))
            if hn:
                pois.setdefault(hn, "중심관광지")
            rs = str(r.get("rlteSignguCd") or "")
            if rs == api_cd:
                rn = norm(r.get("rlteTatsNm"))
                if rn:
                    pois[rn] = r.get("rlteCtgryLclsNm") or "미상"

        cat = collections.defaultdict(collections.Counter)
        for n, c in pois.items():
            cat[c]["total"] += 1
            in_our = n in our_names
            in_sdsc = n in with_coord
            if in_our:
                cat[c]["our"] += 1
            if in_sdsc:
                cat[c]["sdsc"] += 1
            if in_our or in_sdsc:
                cat[c]["either"] += 1

        print(f"  {'카테고리':<10}{'POI':>7}{'우리DB':>9}{'상가정보':>10}{'둘중하나':>10}")
        for c in sorted(cat, key=lambda x: -cat[x]["total"]):
            t = cat[c]["total"]
            print(f"  {c:<10}{t:>7,}{cat[c]['our']/t*100:>8.1f}%"
                  f"{cat[c]['sdsc']/t*100:>9.1f}%{cat[c]['either']/t*100:>9.1f}%")
            for k in ("total", "our", "sdsc", "either"):
                grand_cat[c][k] += cat[c][k]
                grand[k] += cat[c][k]
        print()
        time.sleep(0.2)

    if not grand["total"]:
        return
    print("=" * 58)
    print("표본 합계")
    print("=" * 58)
    print(f"  {'카테고리':<10}{'POI':>7}{'우리DB':>9}{'상가정보':>10}{'둘중하나':>10}")
    for c in sorted(grand_cat, key=lambda x: -grand_cat[x]["total"]):
        g = grand_cat[c]
        t = g["total"]
        print(f"  {c:<10}{t:>7,}{g['our']/t*100:>8.1f}%{g['sdsc']/t*100:>9.1f}%{g['either']/t*100:>9.1f}%")
    t = grand["total"]
    print(f"  {'전체':<10}{t:>7,}{grand['our']/t*100:>8.1f}%"
          f"{grand['sdsc']/t*100:>9.1f}%{grand['either']/t*100:>9.1f}%")
    print()
    print("  '둘중하나' 가 곧 좌표 획득률입니다.")
    print("  이 값이 낮으면 T맵 마스터 전략에서 지도·코스 연결이 그만큼 비게 됩니다.")


if __name__ == "__main__":
    main()
