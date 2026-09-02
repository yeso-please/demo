# 시군구 경계 GeoJSON / TopoJSON

웹 지도용 시군구(약 250개) 경계 데이터. **조인 키는 `SIG_CD`(5자리 행정표준 시군구 코드).**
지역명은 동명이인(예: 광주광역시 자치구 vs 경기 광주시 41610, 여러 시·도의 중구/동구/남구)이 있어 키로 부적합.

## 파일
| 파일 | 설명 | 크기 |
|---|---|---|
| `sig_original.json` | 원본(GeoJSON, 2022-03 기준) | ~3.6MB |
| `sig.json` | 경량화 산출물(TopoJSON, 웹에서 사용) | ~214KB |

`sig.json` 속성은 `SIG_CD`, `SIG_KOR_NM` 두 개만 유지.

> **단순화 비율 주의** — 처음에는 `3%` 였다. 그러면 전체 좌표가 80,710점 → 3,122점
> (지역당 평균 12점)만 남아 시군구 경계가 뭉개지고 "250개로 나뉘어 보이지 않는" 상태가 된다.
> 지도가 이 서비스의 중심 화면이므로 `35%`(약 20,000점)를 쓴다. 산출물 214KB 로 웹에서 충분히 가볍다.

## 원본 출처
- 채택: `vuski/admdongkor` → `ver20220309/ver20220309_sgg_vote_simple.geojson`
  - 필드 `sgg`=표준 SIG_CD(예: 종로구 11110), `SGGNM`=시군구명. 표준 코드체계라 조인 키로 적합.
- 후보였으나 제외: `southkorea/southkorea-maps` `kostat/2018/.../skorea-municipalities-2018-geo.json`
  - 원본 18MB로 해상도는 높지만 `code`가 통계청 센서스 코드(종로구 11010)라 표준 SIG_CD가 아님.
- 최신·정본이 필요하면: 통계청 SGIS(통계지리정보서비스) 또는 공공데이터포털 "행정구역경계(시군구)"
  SHP(컬럼 `SIG_CD`/`SIG_KOR_NM`/`SIG_ENG_NM`)를 받아 아래 명령의 입력으로 사용.

## 경량화 재생성 명령 (mapshaper)
```bash
npm install -g mapshaper
mapshaper sig_original.json \
  -rename-fields SIG_CD=sgg,SIG_KOR_NM=SGGNM \
  -filter-fields SIG_CD,SIG_KOR_NM \
  -simplify 35% keep-shapes \
  -o format=topojson sig.json
```
- `-simplify 35% keep-shapes`: 정점 3%만 유지하되 각 피처가 통째로 사라지지 않도록 보존(작은 섬 유실 방지).
- `-filter-fields`: 속성을 두 개만 남겨 용량 절감.
- 정본 SHP를 원본으로 쓰면 원본이 수 MB~수십 MB라 3% 단순화가 의미 있게 작동해 산출물이 수백 KB 수준이 된다(1MB 이하).

## 최신 행정구역 개편 반영 여부 확인 (한 줄)
```bash
node -e "const t=require('./sig.json');const g=Object.values(t.objects)[0].geometries;console.log(g.find(x=>x.properties.SIG_KOR_NM==='군위군').properties.SIG_CD)"
```
- **`27720`** 이면 2023-07 대구 편입 반영(최신), **`47720`** 이면 경북 시절(구버전).
- 강원특별자치도(2023-06)·전북특별자치도(2024-01)는 시·도 명칭 변경이라 `SIG_KOR_NM`/`SIG_CD`(시군구 단위)에는 영향 없음.
- 현재 `sig.json`은 2022-03 원본이라 군위군이 `47720`(구버전). 최신 반영이 필요하면 위 SGIS/공공데이터포털 정본으로 교체 후 명령 재실행.
