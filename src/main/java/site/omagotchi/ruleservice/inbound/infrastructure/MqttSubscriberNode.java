package site.omagotchi.ruleservice.inbound.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.flow.domain.node.Activatable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class MqttSubscriberNode extends AbstractNode implements MqttCallback, Activatable {

    private final String brokerUrl;
    @Getter
    private final String clientId;
    private final String username;
    private final String password;
    private final String topicFilter;
    private final Counter receivedCounter;
    private MqttAsyncClient mqttAsyncClient;

    // ACTIVE 게이트 상태 - 이 노드가 지금 구독해야 하는지 여부
    @Getter
    private volatile boolean activated = false;

    public MqttSubscriberNode(String id, String brokerUrl, String clientId, String username, String password, String topicFilter
            , MeterRegistry meterRegistry) {
        super(id);

        this.brokerUrl = brokerUrl;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
        this.topicFilter = topicFilter;
        this.receivedCounter = meterRegistry.counter("mqtt.messages.received", "topicFilter", topicFilter);

        addOutputPort("out");
    }

    @Override
    protected void onProcess(Message message) {
        // 빈 구현
    }

    /**
     * 연결까지만 수행하고 구독은 하지 않음
     * 실제 구독은 ACTIVE 전환 시 activate()에서
     */
    @Override
    public void initialize() {
        log.info("[{}] MQTT 연결 시도 - brokerUrl = {}, clientId = {}, topicFilter = {}", getId(), brokerUrl, clientId, topicFilter);

        try {
            MqttConnectionOptions mqttConnectionOptions = new MqttConnectionOptions();

            // 브로커와 연결이 끊기면 자동으로 재연결 시도
            mqttConnectionOptions.setAutomaticReconnect(true);

            // 연결할 때 이전 세션(구독 정보 등)을 이어받을지 새로 시작할지
            mqttConnectionOptions.setCleanStart(false);

            // 연결 끊긴 뒤 몇 초까지 세션을 브로커가 기억해줄지
            mqttConnectionOptions.setSessionExpiryInterval(600L);

            if (username != null && !username.isBlank() && password != null) {
                mqttConnectionOptions.setUserName(username);
                mqttConnectionOptions.setPassword(password.getBytes(StandardCharsets.UTF_8));
            }

            // 구독 전용 노드라 클라이언트 측 영속화가 필요 없음 (발행 없음 + 구독 QoS 1)
            // 기본값인 파일 영속화를 쓰면 작업 디렉터리에 <clientId>-q-mqtt-sub/ 가 생기고 센서 페이로드가 .msg로 남음
            // 세션 재개(cleanStart = false)는 브로커가 clientId로 관리하므로 이 설정과 무관
            mqttAsyncClient = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());

            // 콜백 받을 객체 설정
            mqttAsyncClient.setCallback(this);

            // 브로커 연결 시도
            mqttAsyncClient.connect(mqttConnectionOptions).waitForCompletion();

        } catch (MqttException e) {
            log.error("[{}] MQTT 초기화 실패 (brokerUrl = {})", getId(), brokerUrl, e);
            throw new RuntimeException(e);
        }
        super.initialize();
    }

    @Override
    public synchronized void activate() {
        if (activated) {
            return;
        }

        // 아직 initialize() 전이면 게이트만 열어둠 - 실제 구독은 연결 완료 시 connectComplete()가 게이트 상태에 맞춰 수행
        if (Objects.isNull(this.mqttAsyncClient)) {
            this.activated = true;
            return;
        }

        try {
            mqttAsyncClient.subscribe(topicFilter, 1).waitForCompletion();
            activated = true; // 구독 성공 후에 열어야 실패 시 재시도가 다시 시도됨
            log.info("[{}] 구독 시작 (topicFilter = {})", getId(), topicFilter);
        } catch (MqttException e) {
            log.error("[{}] 구독 시작 실패 (topicFilter = {})", getId(), topicFilter, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void deactivate() {

        // 게이트부터 닫음 (멱등) - 이미 닫혀 있어도 브로커 쪽 구독 해제는 다시 시도해야 함
        // (실패 후 재시도가 여기서 걸러지면 브로커 구독이 영영 남음)
        activated = false;

        if (Objects.isNull(mqttAsyncClient)) {
            return; // 아직 연결 전 - 게이트만 닫으면 됨
        }

        try {
            mqttAsyncClient.unsubscribe(topicFilter).waitForCompletion();
            log.info("[{}] 구독 중단 (topicFilter = {})", getId(), topicFilter);
        } catch (MqttException e) {
            log.error("[{}] 구독 중단 실패 (topicFilter = {})", getId(), topicFilter, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void shutdown() {
        // 게이트를 내려둠 - 이 값이 남아 있으면 stop -> start 시 새 클라이언트의 connectComplete()가 역할 판정 전에 낡은 true를 보고 멋대로 재구독함
        activated = false;

        if (mqttAsyncClient != null) {
            try {
                mqttAsyncClient.disconnect();
                mqttAsyncClient.close();
            } catch (MqttException e) {
                log.warn("[{}] MQTT 종료 중 예외 발생", getId(), e);
            }
        }
        super.shutdown();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        // ACTIVE 게이트 재확인 - unsubscribe 요청 전에 이미 Paho 콜백 큐/in-flight에 들어간 메시지는 구독 해제 뒤에도 여기로 전달될 수 있어서,
        // 강등된 엔진이 새 ACTIVE와 같은 메시지를 중복 처리할 수 있음
        // 카운터보다 먼저 검사 - 폐기한 메시지가 처리 건수로 잡히면 STANDBY가 트래픽을 처리하는 것처럼 보임
        if (!activated) {
            log.debug("[{}] 비활성 상태에서 도착한 잔여 메시지 - 폐기 (topic = {})", getId(), topic);
            return;
        }

        receivedCounter.increment();

        String payloadStr = new String(message.getPayload(), StandardCharsets.UTF_8);

        Message msg = Message.of(Map.of(
                "topic", topic,
                "raw", payloadStr,
                "receivedAt", Instant.now()
        ));

        send("out", msg);
    }

    /**
     * cleanStart(false)라 브로커가 이전 세션의 구독을 기억함
     * 같은 프로세스의 재연결 뿐만 아니라, 프로세스가 통째로 재시작돼서 같은 clientId로 새로 연결하는 경우에도 브로커가 예전 구독을 그대로 복원해서 actiavte() 호출 없이 메시지를 밀어줄 수 있음
     * 그래서 연결이 완료될 때마다(최초 연결 포함) 현재 게이트 상태(activated)와 브로커 쪽 구독 상태를 항상 맞춰줌
     * waitForCompletion()으로 여기서 기다리면 Paho 내부 콜백 스레드가 멈춰서 교착상태나 클라이언트 전체 정체로 이어질 수 있음 (Paho 공식문서 경고)
     * -> 완료를 기다리지 않고 비동기 콜백으로만 결과를 확인함
     * <p>
     * [TODO 알려진 한계, 의도적으로 고치지 않음] activate()/deactivate()는 synchronized(this)를 쥔 채 waitForCompletion()으로 블로킹 대기하는데,
     * 이 메서드도 같은 락(synchronized)이라, 하필 재연결과 activate()/deactivate() 호출이 겹치면 Paho 콜백 스레드가 그 락을 기다리다가 데드락으로 이어질 수 있음 (추정임)
     * 이 메서드의 synchronized를 없애 락 대기 자체를 없애는 수정도 검토했으나, 그러면 activate()/deactivate()가 아직 activated 필드를 갱신하기 전(브로커 응답 대기 중)인 상태를 이 메서드가 그대로 읽어버려서
     * 반대 방향 subscribe/unsubscribe를 동시에 쏘는 다른 레이스(로컬 activated=true인데 브로커는 반대 상태 - 조용한 메시지 유실)가 새로 생겨서 채택 안 함.
     * 확정 안 된 위험끼리의 트레이드오프라 근거 없이 교체하지 않기로 함 - FlowManager의 start/stop/restart 호출이 원인 불명으로 멈추는 로그가 관측되면 그때 재검토.
     */
    @Override
    public synchronized void connectComplete(boolean reconnect, String serverURI) {
        log.info("[{}] MQTT 연결 완료 (reconnect={}, serverURI={})", getId(), reconnect, serverURI);

        try {
            if (activated) {
                mqttAsyncClient.subscribe(topicFilter, 1, null, syncResultListener("구독"));
            } else {
                mqttAsyncClient.unsubscribe(topicFilter, null, syncResultListener("구독 해제"));
            }
        } catch (MqttException e) {
            log.error("[{}] 연결 후 구독 상태 동기화 요청 실패 (topicFilter = {})", getId(), topicFilter, e);
        }
    }

    private MqttActionListener syncResultListener(String action) {
        return new MqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) {
                log.info("[{}] 연결 완료 후 {} 상태 확인 (topicFilter = {})", getId(), action, topicFilter);
            }

            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                log.error("[{}] 연결 후 {} 동기화 실패 (topicFilter = {})", getId(), action, topicFilter, exception);
            }
        };
    }

    @Override
    public void disconnected(MqttDisconnectResponse disconnectResponse) {
        log.warn("[{}] MQTT 연결 끊김: {}", getId(), disconnectResponse.getReasonString());
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        log.error("[{}] MQTT 에러 발생", getId(), exception);
    }

    @Override
    public void deliveryComplete(IMqttToken token) {

    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {

    }
}
