#!/usr/bin/env python3
"""
연관관광지(T맵 기반) API 규모 조사 스크립트.

목적: 학습에 쓸 수 있는 규모인지 판단한다.
  - 중심관광지(seed) 몇 개인가
  - 엣지(seed-연관관광지 쌍) 몇 개인가
  - 250개 시군구 중 몇 곳이 커버되는가
  - 연관관광지 이름이 우리 DB 관광지와 매칭 가능한가

API: 한국관광공사_관광지별 연관 관광지 정보 (data.go.kr/data/15128560)
     http://apis.data.go.kr/B551011/TarRlteTarService1/areaBasedList1
     개발계정 트래픽 100,000회 · 데이터 기간 2024.05 ~ 2025.04

사전 준비:
  공공데이터포털에서 이 API(15128560)에 **활용신청**을 따로 해야 한다.
  기존 국문관광정보(TourAPI) 승인만으로는 호출되지 않는다.
  승인 후 같은 인증키 문자열을 그대로 쓰면 된다.

사용법:
  python tools/rlte_survey.py probe                 # 1) 파라미터 조합 탐색 + 응답 스키마 확인
  python tools/rlte_survey.py collect               # 2) 전량 수집 -> data/rlte/rows.ndjson
  python tools/rlte_survey.py analyze               # 3) 규모 집계 + 판정
  python tools/rlte_survey.py collect --base-ym all # 12개월 전체를 돌 때

인증키 우선순위: --key > 환경변수 TOUR_API_KEY > config/application-secret.yaml (tour.api.key)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

# Windows 콘솔(cp949)에서 한글이 깨지지 않도록 강제 UTF-8 출력
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except Exception:
        pass
from collections import Counter, defaultdict
from pathlib import Path

BASE_URL = "http://apis.data.go.kr/B551011/TarRlteTarService1/areaBasedList1"

ROOT = Path(__file__).resolve().parent.parent
SECRET = ROOT / "config" / "application-secret.yaml"
OUT_DIR = ROOT / "data" / "rlte"
ROWS_FILE = OUT_DIR / "rows.ndjson"
NAMES_FILE = OUT_DIR / "related_names.txt"
GEO_FILE = ROOT / "src" / "main" / "resources" / "static" / "geo" / "sig.json"

# areaCd + signguCd 가 모두 필수다(실측). 전량 페이징은 불가하고 시군구를 순회해야 한다.
# 그리고 우리 sig.json 은 구 행정구역 코드, API 는 신 코드를 쓴다(실측 확인).
#   강원 42 -> 51 (강원특별자치도) · 전북 45 -> 52 (전북특별자치도) · 나머지는 동일
SIDO_REMAP = {"42": "51", "45": "52"}

# 데이터 제공 기간 (명세 기준)
ALL_BASE_YM = [
    "202405", "202406", "202407", "202408", "202409", "202410",
    "202411", "202412", "202501", "202502", "202503", "202504",
]
DEFAULT_BASE_YM = "202504"


# --------------------------------------------------------------------------
# 인증키
# --------------------------------------------------------------------------

def load_key(cli_key: str | None) -> str:
    if cli_key:
        return cli_key.strip()
    env = os.environ.get("TOUR_API_KEY")
    if env:
        return env.strip()
    if SECRET.exists():
        text = SECRET.read_text(encoding="utf-8")
        # tour: > api: > key: <값>  형태에서 첫 key 값을 집는다
        m = re.search(r"tour:\s*\n\s*api:\s*\n\s*key:\s*(.+)", text)
        if m:
            return m.group(1).strip().strip('"').strip("'")
    sys.exit(
        "인증키를 찾지 못했습니다.\n"
        "  --key <키> 로 넘기거나, 환경변수 TOUR_API_KEY 를 설정하거나,\n"
        f"  {SECRET} 에 tour.api.key 가 있어야 합니다."
    )


# --------------------------------------------------------------------------
# 호출
# --------------------------------------------------------------------------

def build_url(key: str, params: dict) -> str:
    """serviceKey 는 인코딩 키/디코딩 키 둘 다 올 수 있어 분기한다."""
    query = urllib.parse.urlencode(params, encoding="utf-8")
    if "%" in key:
        # 이미 퍼센트 인코딩된 '인코딩 키' -> 재인코딩하면 깨진다
        return f"{BASE_URL}?serviceKey={key}&{query}"
    return f"{BASE_URL}?serviceKey={urllib.parse.quote(key, safe='')}&{query}"


def call(key: str, params: dict, timeout: int = 20) -> tuple[int, str]:
    url = build_url(key, params)
    req = urllib.request.Request(url, headers={"User-Agent": "sumeun-rlte-survey/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001
        return -1, f"{type(e).__name__}: {e}"


def parse(body: str) -> tuple[list[dict], int | None, str | None]:
    """(rows, totalCount, error) 반환. data.go.kr 은 오류를 XML/HTML 로 준다."""
    stripped = body.lstrip()
    if not stripped.startswith("{"):
        snippet = " ".join(stripped.split())[:300]
        return [], None, f"JSON 아님 (오류 응답으로 보임): {snippet}"
    try:
        data = json.loads(body)
    except json.JSONDecodeError as e:
        return [], None, f"JSON 파싱 실패: {e}"

    header = data.get("response", {}).get("header", {})
    code = str(header.get("resultCode", ""))
    if code not in ("0000", "00", ""):
        return [], None, f"resultCode={code} resultMsg={header.get('resultMsg')}"

    body_obj = data.get("response", {}).get("body", {}) or {}
    total = body_obj.get("totalCount")
    items = body_obj.get("items")
    if not items:
        return [], (int(total) if total is not None else 0), None
    item = items.get("item") if isinstance(items, dict) else items
    if item is None:
        return [], (int(total) if total is not None else 0), None
    if isinstance(item, dict):
        item = [item]
    return item, (int(total) if total is not None else None), None


# --------------------------------------------------------------------------
# probe
# --------------------------------------------------------------------------

BASE_PARAMS = {
    "MobileOS": "ETC",
    "MobileApp": "SumeunSurvey",
    "_type": "json",
    "numOfRows": "10",
    "pageNo": "1",
}


def cmd_probe(args) -> None:
    key = load_key(args.key)
    print(f"엔드포인트: {BASE_URL}")
    print(f"인증키: {'인코딩 키로 보임' if '%' in key else '디코딩 키로 보임'} (길이 {len(key)})\n")

    attempts = [
        ("최소 파라미터", {}),
        ("baseYm 추가", {"baseYm": DEFAULT_BASE_YM}),
        ("baseYm + 시도(강원)", {"baseYm": DEFAULT_BASE_YM, "areaCd": "51"}),
        ("baseYm + 시군구(강릉)", {"baseYm": DEFAULT_BASE_YM, "areaCd": "51", "signguCd": "51150"}),
    ]

    winner = None
    for label, extra in attempts:
        params = {**BASE_PARAMS, **extra}
        status, body = call(key, params)
        rows, total, err = parse(body)
        print(f"[{label}] HTTP {status} · totalCount={total} · rows={len(rows)}")
        if err:
            print(f"    실패: {err}")
        elif rows:
            print("    성공")
            if winner is None:
                winner = (label, extra, rows, total)
        else:
            print("    응답은 정상이나 결과 0건")
        time.sleep(0.3)

    if not winner:
        print("\n어떤 조합도 데이터를 못 받았습니다. 확인할 것:")
        print("  1) data.go.kr 에서 이 API(15128560) 활용신청이 승인됐는지")
        print("  2) 인증키를 '디코딩 키'로 넣었는지 (--key 로 직접 지정해 재시도)")
        print("  3) 승인 직후면 반영에 시간이 걸릴 수 있음")
        sys.exit(1)

    label, extra, rows, total = winner
    print(f"\n=== 동작한 조합: {label} ===")
    print(f"필수로 보이는 추가 파라미터: {extra or '없음'}")
    print(f"totalCount: {total}")
    print("\n--- 응답 항목(필드명) ---")
    for k, v in rows[0].items():
        sample = str(v)
        if len(sample) > 60:
            sample = sample[:60] + "..."
        print(f"  {k:<24} = {sample}")

    print("\n--- 첫 행 원본 ---")
    print(json.dumps(rows[0], ensure_ascii=False, indent=2))
    print("\n다음 단계:  python tools/rlte_survey.py collect")


# --------------------------------------------------------------------------
# collect
# --------------------------------------------------------------------------

def load_regions() -> list[tuple[str, str, str]]:
    """sig.json 에서 (우리 SIG_CD, API areaCd, API signguCd) 250쌍을 만든다."""
    if not GEO_FILE.exists():
        sys.exit(f"{GEO_FILE} 가 없습니다.")
    geo = json.loads(GEO_FILE.read_text(encoding="utf-8"))
    geoms = geo["objects"]["sig_original"]["geometries"]
    out = []
    for g in geoms:
        sig = g["properties"]["SIG_CD"]
        api_sido = SIDO_REMAP.get(sig[:2], sig[:2])
        out.append((sig, api_sido, api_sido + sig[2:]))
    return out


def collect_region(key: str, area_cd: str, signgu_cd: str, our_sig: str,
                   base_ym: str, page_size: int, sleep: float, out) -> int:
    """한 시군구/한 기준월을 전량 페이징. 저장한 행 수 반환."""
    page, saved, total = 1, 0, None
    while True:
        params = {
            **BASE_PARAMS,
            "numOfRows": str(page_size),
            "pageNo": str(page),
            "baseYm": base_ym,
            "areaCd": area_cd,
            "signguCd": signgu_cd,
        }
        status, body = call(key, params)
        rows, t, err = parse(body)
        if err:
            print(f"\n    {signgu_cd} page {page} 실패: {err}")
            break
        if total is None:
            total = t or 0
        if not rows:
            break
        for r in rows:
            r["_sigCd"] = our_sig      # 우리 DB SIG_CD (구 코드) — 조인 키
            r["_baseYm"] = base_ym
            out.write(json.dumps(r, ensure_ascii=False) + "\n")
        saved += len(rows)
        if total and saved >= total:
            break
        if len(rows) < page_size:
            break
        page += 1
        time.sleep(sleep)
    return saved


def cmd_collect(args) -> None:
    key = load_key(args.key)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    regions = load_regions()
    months = ALL_BASE_YM if args.base_ym == "all" else [args.base_ym]
    print(f"시군구 {len(regions)}곳 x 기준월 {len(months)}개")
    print(f"저장 위치: {ROWS_FILE}\n")

    total_saved = 0
    empty: list[str] = []
    with ROWS_FILE.open("w", encoding="utf-8") as out:
        for ym in months:
            for i, (sig, area_cd, signgu_cd) in enumerate(regions, 1):
                n = collect_region(key, area_cd, signgu_cd, sig, ym,
                                   args.page_size, args.sleep, out)
                total_saved += n
                if n == 0:
                    empty.append(f"{sig}->{signgu_cd}")
                print(f"  [{ym}] {i:>3}/{len(regions)} {signgu_cd} · "
                      f"{n:>5}건 · 누적 {total_saved:,}", end="\r")
                time.sleep(args.sleep)
            print()

    print(f"\n총 {total_saved:,}건 저장 완료.")
    if empty:
        shown = ", ".join(empty[:15])
        more = f" 외 {len(empty) - 15}곳" if len(empty) > 15 else ""
        print(f"0건으로 나온 시군구 {len(empty)}곳: {shown}{more}")
        print("  (코드 매핑이 틀렸을 수 있습니다 — SIDO_REMAP 확인)")
    print("다음 단계:  python tools/rlte_survey.py analyze")


# --------------------------------------------------------------------------
# analyze
# --------------------------------------------------------------------------

def pick(row: dict, *needles: str) -> str | None:
    """필드명을 모르므로 부분 문자열로 찾는다. 첫 매치의 키를 반환."""
    for k in row:
        low = k.lower()
        if all(n.lower() in low for n in needles):
            return k
    return None


def cmd_analyze(args) -> None:
    if not ROWS_FILE.exists():
        sys.exit(f"{ROWS_FILE} 가 없습니다. 먼저 collect 를 실행하세요.")

    rows = [json.loads(line) for line in ROWS_FILE.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows:
        sys.exit("수집된 행이 없습니다.")

    sample = rows[0]
    # 실측으로 확인된 필드명을 우선 사용하고, 없으면 부분 문자열로 탐지한다.
    def field(known: str, *needles: str) -> str | None:
        return known if known in sample else pick(sample, *needles)

    k_hub = args.hub or field("tAtsNm", "tats", "nm")
    k_rlte = args.rlte or field("rlteTatsNm", "rlte", "tats", "nm")
    k_signgu = field("_sigCd", "signgu", "cd")
    k_area = field("areaNm", "area", "nm")
    k_ctgry = field("rlteCtgryLclsNm", "ctgry", "lcls")
    k_rank = field("rlteRank", "rank")
    k_rlte_sgg = field("rlteSignguCd", "rlte", "signgu", "cd")

    print("=== 탐지된 필드 ===")
    print(f"  중심관광지(hub) : {k_hub}")
    print(f"  연관관광지      : {k_rlte}")
    print(f"  시군구          : {k_signgu}")
    print(f"  시도            : {k_area}")
    print(f"  카테고리        : {k_ctgry}")
    print(f"  순위            : {k_rank}")
    print(f"\n  (자동 탐지가 틀렸다면 아래 전체 키를 보고 --hub/--rlte 로 직접 지정하세요)")
    print(f"  전체 키: {list(sample.keys())}\n")

    if not k_hub or not k_rlte:
        sys.exit("중심/연관 관광지 필드를 못 찾았습니다. --hub, --rlte 로 지정해주세요.")

    hubs = set()
    rltes = set()
    edges = set()
    per_hub = Counter()
    per_signgu_hubs = defaultdict(set)
    ctgry = Counter()
    cross_region = 0

    for r in rows:
        h = (r.get(k_hub) or "").strip()
        t = (r.get(k_rlte) or "").strip()
        if not h or not t:
            continue
        hubs.add(h)
        rltes.add(t)
        edges.add((h, t))
        per_hub[h] += 1
        if k_signgu:
            per_signgu_hubs[str(r.get(k_signgu))].add(h)
        if k_ctgry:
            ctgry[str(r.get(k_ctgry))] += 1
        # 연관관광지가 다른 시군구인 경우 = 지역 간 이동 신호
        if k_rlte_sgg and r.get("signguCd") and r.get(k_rlte_sgg) != r.get("signguCd"):
            cross_region += 1

    counts = sorted(per_hub.values())
    n = len(counts)
    median = counts[n // 2] if n else 0

    print("=== 규모 ===")
    print(f"  총 행 수                 : {len(rows):,}")
    print(f"  중심관광지(seed) 수      : {len(hubs):,}")
    print(f"  연관관광지 고유 수       : {len(rltes):,}")
    print(f"  고유 엣지(seed-연관) 수  : {len(edges):,}")
    print(f"  seed 당 엣지 (중앙값)    : {median}")
    print(f"  seed 당 엣지 (최소/최대) : {counts[0] if n else 0} / {counts[-1] if n else 0}")
    if k_rlte_sgg:
        pct = cross_region / len(rows) * 100 if rows else 0
        print(f"  시군구 밖으로 나가는 엣지: {cross_region:,} ({pct:.1f}%)")
        print("    -> 이 비율이 높으면 지역 간 이동 신호로도 쓸 수 있습니다")

    if k_signgu:
        covered = len([s for s, v in per_signgu_hubs.items() if v])
        print(f"\n=== 지역 커버리지 ===")
        print(f"  시군구 수                : {covered} / 250 ({covered / 250 * 100:.1f}%)")
        hubs_per_sgg = sorted(len(v) for v in per_signgu_hubs.values())
        if hubs_per_sgg:
            print(f"  시군구당 seed (중앙값)   : {hubs_per_sgg[len(hubs_per_sgg) // 2]}")
            print(f"  시군구당 seed (최소/최대): {hubs_per_sgg[0]} / {hubs_per_sgg[-1]}")

    if ctgry:
        print(f"\n=== 카테고리 분포 (상위 10) ===")
        for c, v in ctgry.most_common(10):
            print(f"  {c:<24} {v:,}")

    # 나중에 우리 DB 관광지명과 매칭할 수 있도록 이름만 따로 저장
    NAMES_FILE.write_text("\n".join(sorted(rltes)), encoding="utf-8")
    print(f"\n연관관광지 이름 {len(rltes):,}개를 {NAMES_FILE} 에 저장했습니다.")
    print("  (우리 DB 관광지 6,769 / 음식점 8,540 과의 이름 매칭률은 이 파일로 별도 확인)")

    # --- 판정 ---
    print("\n" + "=" * 58)
    print("판정")
    print("=" * 58)
    e = len(edges)
    s = len(hubs)
    if e >= 100_000 and s >= 2_000:
        print("  충분: contrastive learning 학습이 가능한 규모입니다.")
    elif e >= 30_000 and s >= 500:
        print("  경계: 학습은 가능하나 과적합 위험이 큽니다.")
        print("        - 강한 정규화 / 작은 임베딩 차원 / 교차검증 필수")
        print("        - baseYm 12개월을 모두 모아 규모를 키우는 것을 권장 (--base-ym all)")
    else:
        print("  부족: 이 규모로는 표현 학습이 성립하기 어렵습니다.")
        print("        - 학습 대신 그래프 기반 유사도(co-membership)로 방향 전환을 검토하세요")
        print("        - 또는 T맵 엣지를 '학습 데이터'가 아니라 '평가 정답지'로만 쓰는 설계")
    print()
    print("  주의: seed 는 지역 대표 관광지일 가능성이 높습니다(popularity-biased).")
    print("        평가 시 반드시 인기도 계층별(head/tail) Recall 을 분리해 보고하세요.")
    print("        전체 Recall 만 보고하면 순환 논증이 됩니다.")


# --------------------------------------------------------------------------

def main() -> None:
    p = argparse.ArgumentParser(description="연관관광지 API 규모 조사")
    p.add_argument("--key", help="공공데이터포털 인증키 (디코딩 키 권장)")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("probe", help="파라미터 조합 탐색 + 응답 스키마 확인")
    sp.set_defaults(func=cmd_probe)

    sc = sub.add_parser("collect", help="전량 수집")
    sc.add_argument("--base-ym", default=DEFAULT_BASE_YM,
                    help=f"기준월 YYYYMM 또는 all (기본 {DEFAULT_BASE_YM}, 제공범위 202405~202504)")
    sc.add_argument("--page-size", type=int, default=1000)
    sc.add_argument("--sleep", type=float, default=0.2, help="페이지 간 대기(초)")
    sc.set_defaults(func=cmd_collect)

    sa = sub.add_parser("analyze", help="규모 집계 + 판정")
    sa.add_argument("--hub", help="중심관광지 필드명 직접 지정")
    sa.add_argument("--rlte", help="연관관광지 필드명 직접 지정")
    sa.set_defaults(func=cmd_analyze)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
