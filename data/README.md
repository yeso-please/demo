# 로컬 데이터 디렉터리

이 디렉터리의 실제 데이터는 Git에 올리지 않습니다. 문서와 작은 테스트 샘플만 버전 관리합니다.

## 자동 생성 파일

| 경로 | 설명 | 공유 방식 |
|---|---|---|
| `sumeun.mv.db` | 개발용 H2 DB. 계정·여행기 등 로컬 데이터가 들어갈 수 있음 | 공유 금지, 각자 생성 |
| `sumeun.trace.db` | H2 진단 파일 | 공유 금지 |
| `rlte/rows.ndjson` | 관광지별 연관 관광지 API 수집 결과 | 스크립트로 재수집 |
| `rlte/related_names.txt` | 연관 관광지 이름 목록 | 스크립트로 재생성 |

## 애플리케이션 데이터 재현

1. `config/application-secret.yaml`에 TourAPI 키를 설정합니다.
2. 애플리케이션을 실행하면 빈 H2 DB와 250개 시군구가 생성됩니다.
3. 루트 [README.md](../README.md)의 데이터 동기화 명령을 실행합니다.
4. TourAPI 호출 예산이 소진되면 다음 날 이어서 실행합니다.

기존 개발 DB 파일을 복사하면 개인 계정·여행기·저장 코스가 함께 전달될 수 있으므로 사용하지 않습니다.

## 연관 관광지 연구 데이터 재현

공공데이터포털의 `한국관광공사_관광지별 연관 관광지 정보` 활용 신청 후 다음 도구를 사용합니다.

```powershell
python tools/rlte_survey.py --key YOUR_DATA_GO_KR_KEY probe
python tools/rlte_survey.py --key YOUR_DATA_GO_KR_KEY collect --base-ym all
python tools/rlte_survey.py analyze
```

명령의 최신 옵션은 다음으로 확인합니다.

```powershell
python tools/rlte_survey.py --help
```

팀에서 동일한 고정 스냅샷이 꼭 필요하다면 Organization Drive 같은 접근 제한 저장소에 압축 파일과 SHA-256을 함께 보관합니다. 공개 데이터라도 원 제공처의 재배포 조건을 먼저 확인합니다.
