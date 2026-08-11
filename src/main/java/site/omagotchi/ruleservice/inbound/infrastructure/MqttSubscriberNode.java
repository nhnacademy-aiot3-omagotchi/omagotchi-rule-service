package site.omagotchi.ruleservice.inbound.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;
import site.omagotchi.ruleservice.flow.domain.node.Activatable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Slf4j
public class MqttSubscriberNode extends AbstractNode implements MqttCallback, Activatable {

    private final String brokerUrl;
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

            mqttAsyncClient = new MqttAsyncClient(brokerUrl, clientId);

            // 콜백 받을 객체 설정
            mqttAsyncClient.setCallback(this);

            // 브로커 연결 시도
            mqttAsyncClient.connect(mqttConnectionOptions).waitForCompletion();

        } catch (MqttException e) {
            log.error("[{}] MQTT 초기화 실패 (brokerUrl={})", getId(), brokerUrl, e);
            throw new RuntimeException(e);
        }
        super.initialize();
    }

    @Override
    public synchronized void activate() {
        if (activated) {
            return;
        }

        try {
            mqttAsyncClient.subscribe(topicFilter, 1).waitForCompletion();
            activated = true;
            log.info("[{}] 구독 시작 (topicFilter = {})", getId(), topicFilter);
        } catch (MqttException e) {
            log.error("[{}] 구독 시작 실패 (topicFilter = {})", getId(), topicFilter, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void deactivate() {
        if (!activated) {
            return;
        }

        try {
            mqttAsyncClient.unsubscribe(topicFilter).waitForCompletion();
            activated = false;
            log.info("[{}] 구독 중단 (topicFilter = {})", getId(), topicFilter);
        } catch (MqttException e) {
            log.error("[{}] 구독 중단 실패 (topicFilter = {})", getId(), topicFilter, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void shutdown() {

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
     * 같은 프로세스의 재연결 뿐만 아니라, 프로세스가 통째로 재시작돼서 같은 clientId로 새로 연결하는 경우에도 브로커가 예전 구독을 그대로 복원해서 activate() 호출 없이 메시지를 밀어줄 수 있음
     * 그래서 연결이 완료될 때마다(최초 연결 포함) 현재 게이트 상태(activated)와 브로커 쪽 구독 상태를 항상 맞춰줌
     */
    @Override
    public synchronized void connectComplete(boolean reconnect, String serverURI) {
        log.info("[{}] MQTT 연결 완료 (reconnect={}, serverURI={})", getId(), reconnect, serverURI);

        try {
            if (activated) {
                mqttAsyncClient.subscribe(topicFilter, 1).waitForCompletion();
                log.info("[{}] 연결 완료 후 구독 상태 확인 (topicFilter = {})", getId(), topicFilter);
            } else {
                mqttAsyncClient.unsubscribe(topicFilter).waitForCompletion();
                log.info("[{}] 연결 완료 후 STANDBY 상태이므로 구독 해제 확인 (topicFilter = {})", getId(), topicFilter);
            }
        } catch (MqttException e) {
            log.error("[{}] 연결 후 구독 상태 동기화 실패 (topicFilter = {})", getId(), topicFilter, e);
        }
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

    public String getClientId() {
        return clientId;
    }
}
