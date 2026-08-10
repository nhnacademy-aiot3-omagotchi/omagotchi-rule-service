# Rule Service

센서 이벤트를 수신·정규화하고 설정된 룰을 평가하는 서비스입니다.

## IntelliJ 로컬 실행

1. 저장소 루트에서 `.env.local`을 만듭니다.

   ```bash
   cp .env.local.example .env.local
   ```

2. `RuleServiceApplication` 실행 설정을 지정합니다.

   - Active profiles: `local`
   - Working directory: `rule-service` 저장소 루트
   - JDK: 21

`.env.local`은 `application-local.yaml`에서 필수로 읽으므로 별도의 IntelliJ 플러그인은 필요하지 않습니다. 기본값은 Eureka 연결을 끄며, Discovery Service도 함께 실행할 때만 `EUREKA_ENABLED=true`로 변경합니다.

Identity에서 로컬 RSA key pair를 생성했다면 Rule은 private key를 복사하지 않고 `../identity-service/secrets/jwt-public.pem`을 참조합니다. 저장소 배치가 다를 때만 `.env.local`의 `JWT_PUBLIC_KEY_LOCATION`을 변경합니다.

RabbitMQ·MQTT·InfluxDB와 Learning Service 주소는 `.env.local.example`의 로컬 예시를 기준으로 준비합니다. InfluxDB Token을 포함한 실제 Credential은 Git에서 제외된 `.env.local`에만 입력합니다.

## 환경별 설정 계약

- `local`: 필수 `./.env.local`을 읽고 Eureka는 기본적으로 비활성화합니다.
- `test`: `.env.local`을 읽지 않고 `application-test.yaml`과 테스트 RSA Key만 사용합니다.
- `prod`: env 파일을 import하지 않습니다. RabbitMQ·MQTT·InfluxDB·Eureka·JWT와 내부 서비스 주소를 환경변수와 Mount된 공개키로 주입해야 합니다. MQTT username/password는 Broker 인증 정책에 따라 선택적으로 사용합니다.

공통 `application.yaml`은 익명 MQTT 연결을 제외한 운영 필수 설정에 fallback을 두지 않습니다. 필수값 누락이나 읽을 수 없는 JWT 공개키는 요청을 받기 전에 애플리케이션 시작을 실패시킵니다. 외부 시스템의 연결 가능 여부와 인증 성공 여부는 각 클라이언트와 Healthcheck에서 확인합니다. 이 설정 경계는 Eureka를 통한 다중 인스턴스 등록과 별개이며, 단일 서버에서도 동일하게 적용됩니다.

## 확인 경로

```text
GET /api/v1/rules/ping
GET /actuator/health
```

Smoke Test 경로를 변경하면 Gateway route와 Infra 배포 검증 경로도 함께 동기화해야 합니다.

## Secret 관리

- 실제 값은 `.env.local`에만 저장하고 `.env.local.example`에는 변수명과 비밀이 아닌 예시값만 기록합니다.
- `.env.local`, 비밀번호, Token, 인증서, 개인키는 Git에 포함하지 않습니다.
