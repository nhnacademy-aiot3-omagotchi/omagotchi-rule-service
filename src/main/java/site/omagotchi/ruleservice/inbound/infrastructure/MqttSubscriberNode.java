package site.omagotchi.ruleservice.inbound.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.node.AbstractNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Slf4j
public class MqttSubscriberNode extends AbstractNode implements MqttCallback {

    private final String brokerUrl;
    private final String clientId;
    private final String username;
    private final String password;
    private final String topicFilter;
    private final Counter receivedCounter;
    private MqttAsyncClient mqttAsyncClient;

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

    @Override
    public void initialize() {
        try {
            MqttConnectionOptions mqttConnectionOptions = new MqttConnectionOptions();
            //브로커와 연결이 끊기면 자동으로 재연결 시도
            mqttConnectionOptions.setAutomaticReconnect(true);
            //연결할 때 이전 세션(구독 정보 등)을 이어받을지 새로 시작할지
            mqttConnectionOptions.setCleanStart(false);
            //연결 끊긴 뒤 몇 초까지 세션을 브로커가 기억해줄지
            mqttConnectionOptions.setSessionExpiryInterval(600L);
            if (username != null && !username.isBlank()) {
                mqttConnectionOptions.setUserName(username);
                mqttConnectionOptions.setPassword(password.getBytes(StandardCharsets.UTF_8));
            }

            mqttAsyncClient = new MqttAsyncClient(brokerUrl, clientId);
            //콜백 받을 객체 설정
            mqttAsyncClient.setCallback(this);
            //브로커 연결 시도
            mqttAsyncClient.connect(mqttConnectionOptions).waitForCompletion();
            //토픽으로 구독 신청
            mqttAsyncClient.subscribe(topicFilter, 1);

        } catch (MqttException e) {
            log.error("[{}] MQTT 초기화 실패 (brokerUrl={})", getId(), brokerUrl, e);
            throw new RuntimeException(e);
        }
        super.initialize();
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

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("[{}] MQTT 연결 완료 (reconnect={}, serverURI={})", getId(), reconnect, serverURI);
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
