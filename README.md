# Omagotchi Rule Service

MQTT 센서 이벤트의 수집·정규화·품질 검사·룰 평가·저장을 담당하는 서비스입니다.

## 역할

- 센서 수집: MQTT v5 이벤트 구독
- 데이터 처리: 정규화, 프레임 검사, 물리 범위 검사, 이상 상태 탐지
- 룰 평가: Learning Service 룰 동기화, 인메모리 캐시, 임계값 평가
- 메시지 전달: 원본·품질 이벤트 RabbitMQ 발행, 발행 실패 재시도
- 시계열 저장: 원본 데이터 InfluxDB 적재, 시간 단위 Downsampling
- 장애 복구: 원본 큐 처리 실패 추적, Parking Queue 재처리
- API 보호: JWT 검증, 시스템 관리자 권한 적용

## 처리 흐름

```text
MQTT → 정규화 → 품질 검사·룰 평가 → RabbitMQ → InfluxDB
```

- 초기 룰 적재: Learning Service HTTP 조회
- 룰 변경 반영: RabbitMQ Fanout 이벤트
- 룰 누락 보정: 5분 주기 전체 재동기화
- 추적 식별자: HTTP `requestId`, 파이프라인 `traceId`

## 로컬 실행

- 런타임: JDK 21
- 빌드 도구: Maven Wrapper
- 필수 의존성: RabbitMQ, MQTT Broker, InfluxDB, Learning Service
- 인증 의존성: Identity Service의 JWT 공개키
- 테스트 의존성: Docker 호환 Container Runtime
- 선택 의존성: Discovery Service

```bash
cp .env.local.example .env.local
./mvnw verify
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

> **기존에 `.env.local`을 쓰고 있었다면 아래 3개 키를 반드시 추가해야 합니다.**
> 없으면 `Could not resolve placeholder 'ENGINE_ID'`로 **기동 자체가 실패합니다.**
> (`EUREKA_ENABLED=false`여도 항상 바인딩되므로 단일 엔진으로 쓰더라도 필요합니다.)
>
> ```dotenv
> ENGINE_ID=engine-a
> ENGINE_PRIORITY=1
> INTERNAL_SHARED_SECRET=dummy
> ```
>
> 기본값을 주지 않는 건 의도된 설계입니다 — 두 엔진이 같은 `ENGINE_ID`로 뜨면 서로를 자기 자신으로 오인해 둘 다 ACTIVE가 되는 사고가 나므로, 누락을 기동 시점에 바로 잡습니다.

- 기본 주소: <http://localhost:8081>
- 상태 확인: <http://localhost:8081/actuator/health>
- Smoke Test: <http://localhost:8081/api/v1/rules/ping>
- Discovery 사용: `.env.local`의 `EUREKA_ENABLED=true`

### IntelliJ 설정

- Main class: `site.omagotchi.ruleservice.RuleServiceApplication`
- Active profiles: `local`
- Working directory: `rule-service` 저장소 루트
- JDK: 21

`.env.local`은 `application-local.yaml`에서 필수로 읽습니다. Identity와 같은 상위 디렉터리에 저장소를 배치한 경우 기본 JWT 공개키 경로인 `../identity-service/secrets/jwt-public.pem`을 사용합니다.

## 환경별 설정

- `local`: `./.env.local` 필수, Eureka 기본 비활성화
- `test`: 로컬 env 파일 미사용, 테스트 설정·RSA Key·Testcontainers 사용
- `prod`: env 파일 자동 로드 없음, 배포 환경에서 필수값 주입

### 운영 필수 설정

- 애플리케이션: `SERVER_PORT`, `CORE_BASE_URL`
- 엔진 식별: `ENGINE_ID`, `ENGINE_PRIORITY`
- 이중화 기대치: `ENGINE_EXPECTED_PEER_COUNT` (기본 1 — A/B 구성 기준, 단일 엔진 운영 시 0) 
- 엔진 간 내부 통신: `INTERNAL_SHARED_SECRET`
- Discovery: `EUREKA_ENABLED`, `EUREKA_URL`
- JWT: `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_PUBLIC_KEY_LOCATION`
- MQTT: `SENSOR_BROKER_URL`, `SENSOR_CLIENT_ID`
- RabbitMQ: `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`
- InfluxDB: `INFLUXDB_URL`, `INFLUXDB_TOKEN`, `INFLUXDB_ORG_ID`

- MQTT Username·Password: Broker의 익명 연결 허용 시 생략 가능
- 필수 설정 누락: 애플리케이션 기동 실패
- JWT 공개키 누락·읽기 실패: 애플리케이션 기동 실패
- 외부 시스템 연결·인증 실패: Client·Healthcheck에서 확인

## 디렉터리 구조

기준 경로: `src/main/java/site/omagotchi/ruleservice/`

- `flow/`: 처리 파이프라인 구성·실행·제어 API
- `inbound/`: MQTT 수신·센서 데이터 정규화
- `quality/`: 데이터 품질 검사·센서 이상 탐지
- `rule/`: 룰 동기화·캐시·평가
- `messaging/`: RabbitMQ 발행·Topology·재시도
- `writer/`: InfluxDB 저장·Bucket 초기화
- `recovery/`: 실패 추적·Parking Queue 재처리
- `global/`: 환경 설정·JWT 보안·예외·요청 추적
- `src/main/resources/flows/`: 정적 Flow 정의
- `src/main/resources/flux/`: InfluxDB Downsampling Script

## API 경계

- 공개 경로: `GET /api/v1/rules/ping`, `/actuator/health`, `/actuator/info`
- 관리자 경로: `/api/v1/rules/**`, `/api/v1/flows/**`
- 관리자 권한: `ROLE_SYSTEM_ADMIN`
- 기타 경로: 기본 거부

Smoke Test 경로 변경 시 Gateway Route와 Infra 배포 검증 경로의 동시 변경이 필요합니다.

## Secret 관리

- 로컬 실제 값: Git에서 제외된 `.env.local`
- 운영 Credential: 저장소 밖 Secret env 파일 또는 배포 환경변수
- 운영 JWT 공개키: 읽기 전용 Mount 파일
- 예시 파일: 변수명과 비밀이 아닌 예시값만 기록
- Git 제외 대상: `.env.local`, 비밀번호, Token, 인증서, 개인키
