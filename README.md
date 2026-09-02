# 숨은 여행

방문했던 여행의 기억을 짧은 글로 남기면, 비슷한 결을 가진 새로운 국내 여행 코스를 발견하도록 돕는 Spring Boot 프로젝트입니다.

## 개발 환경

- Java 21
- Spring Boot 4.1
- Gradle Wrapper
- H2 file database
- TourAPI, Kakao Maps/Mobility, Gemini API

## 처음 실행하기

1. 저장소를 clone 합니다.

   ```powershell
   git clone https://github.com/yeso-please/demo.git
   cd demo
   ```

2. 로컬 비밀 설정을 만듭니다.

   ```powershell
   Copy-Item config/application-secret.example.yaml config/application-secret.yaml
   ```

   `config/application-secret.yaml`에 각자 발급받은 키를 입력합니다. 이 파일은 Git에 포함되지 않습니다.

3. 테스트를 실행합니다.

   ```powershell
   .\gradlew.bat test
   ```

4. 서버를 실행합니다.

   ```powershell
   .\gradlew.bat bootRun
   ```

5. 브라우저에서 <http://localhost:8080>을 엽니다.

최초 실행 시 `data/sumeun.mv.db`가 자동으로 생성되고, 전국 시군구와 특산물 seed가 적재됩니다. 관광지·음식점·공식 코스는 아래 데이터 동기화를 실행해야 채워집니다.

## 관광 데이터 채우기

서버를 실행한 상태에서 PowerShell로 호출합니다.

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/admin/sync/tour
Invoke-RestMethod -Method Post http://localhost:8080/admin/sync/tour/course-points
Invoke-RestMethod -Method Post http://localhost:8080/admin/sync/goodprice
```

TourAPI 일일 호출 한도 때문에 전국 데이터가 한 번에 끝나지 않을 수 있습니다. 남은 호출량은 다음 주소에서 확인합니다.

```powershell
Invoke-RestMethod http://localhost:8080/admin/sync/tour/budget
```

상세 설명 보강은 매일 오전 4시에 실행되며, 수동 실행과 진행률 확인도 가능합니다.

```powershell
Invoke-RestMethod -Method Post 'http://localhost:8080/admin/sync/tour/details?limit=5'
Invoke-RestMethod http://localhost:8080/admin/sync/tour/details/progress
```

데이터 규모와 제약은 [docs/DATA.md](docs/DATA.md), 추천 설계는 [docs/PRD.md](docs/PRD.md)를 먼저 확인해 주세요.

## 저장소에 올리지 않는 파일

- `config/application-secret.yaml`: 실제 API 키
- `data/*.mv.db`: 개인별 H2 DB
- `data/rlte/rows.ndjson`: 재생성 가능한 대용량 조사 데이터
- `uploads/`: 사용자 업로드 사진
- `build/`, `*.log`: 빌드와 실행 산출물

대용량 데이터는 Git으로 공유하지 않습니다. 재현 방법은 [data/README.md](data/README.md)에 정리되어 있습니다.

## 협업

`main`에 직접 작업하지 않고 기능 브랜치와 Pull Request를 사용합니다.

```powershell
git switch -c feature/작업이름
git add <변경한 파일>
git commit -m "feat: 작업 설명"
git push -u origin feature/작업이름
```

자세한 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 참고해 주세요.
