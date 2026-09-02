# 협업 규칙

## 브랜치

- `main`: 항상 테스트를 통과하는 공유 기준 브랜치
- `feature/<이름>`: 기능 개발
- `fix/<이름>`: 버그 수정
- `docs/<이름>`: 문서 수정

한 브랜치에는 가능한 한 하나의 작업만 담고, Pull Request로 `main`에 병합합니다.

## 작업 시작

```powershell
git switch main
git pull --ff-only origin main
git switch -c feature/작업이름
```

## 커밋 전 확인

```powershell
.\gradlew.bat test
git status
```

`git add -A`를 습관적으로 사용하지 말고, `git status`에서 실제 공유할 파일을 확인한 뒤 파일명을 지정해 stage 합니다.

## 커밋 메시지

- `feat:` 기능 추가
- `fix:` 버그 수정
- `refactor:` 동작을 바꾸지 않는 구조 개선
- `docs:` 문서
- `test:` 테스트
- `chore:` 설정과 도구

예: `feat: 여행기 기반 공식 코스 추천 추가`

## 금지 파일

- 실제 API 키와 비밀번호
- `application-secret.yaml`
- H2 DB와 사용자 데이터
- 사용자 업로드 사진
- 실행 로그와 빌드 결과

키가 실수로 커밋됐다면 파일만 지우지 말고 즉시 키를 폐기·재발급한 뒤 저장소 관리자에게 알립니다.
