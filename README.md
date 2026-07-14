# Rule Service

센서 이벤트를 수신·정규화하고 설정된 룰을 평가하는 서비스입니다.

## IntelliJ 로컬 실행

1. 저장소 루트에서 `.env`를 만듭니다.

   ```bash
   cp .env.example .env
   ```

2. `RuleServiceApplication` 실행 설정을 지정합니다.

   - Active profiles: `local`
   - Working directory: `rule-service` 저장소 루트
   - JDK: 21

`.env`는 `application-local.yaml`에서 읽으므로 별도의 IntelliJ 플러그인은 필요하지 않습니다. 기본값은 Eureka 연결을 끄며, Discovery Service도 함께 실행할 때만 `EUREKA_ENABLED=true`로 변경합니다.

## 확인 경로

```text
GET /api/rules/ping
GET /actuator/health
```

## Secret 관리

- 실제 값은 `.env`에만 저장하고 `.env.example`에는 변수명과 예시값만 기록합니다.
- `.env`, 비밀번호, Token, 인증서, 개인키는 Git에 포함하지 않습니다.
