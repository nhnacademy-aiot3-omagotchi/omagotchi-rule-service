package site.omagotchi.ruleservice.inbound.infrastructure;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import site.omagotchi.ruleservice.flow.domain.Message;
import site.omagotchi.ruleservice.flow.domain.connection.Connection;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MQTT 브로커(Testcontainers)로 activate()/deactivate() 시 구독 상태에 따라
 * 메시지 수신 여부가 실제로 달라지는지 검증 - mock으로는 증명 못 하는 부분(브로커 레벨 구독 해제 보장)
 * 브로커 재시작 시나리오는 MqttSubscriberNode.connectComplete()의 재구독 방지 로직을 검증하기 위함
 * <p>
 * 브로커 포트는 고정 바인딩 사용 - 이 환경에서는 docker restart 시 Testcontainers의 동적 포트가
 * 재할당돼서(같은 컨테이너인데도 호스트 포트가 바뀜) node의 automaticReconnect가 죽은 옛 포트로
 * 영원히 재시도하는 문제가 있어, 재시작 전후로 포트가 절대 안 바뀌도록 고정함
 */
@Testcontainers
class MqttSubscriberNodeStandbyTest {

    private static final String TOPIC = "test/topic";
    private static final int MOSQUITTO_PORT = 18883;

    @Container // static 없음 - 테스트 메서드마다 새 컨테이너
    GenericContainer<?> mosquitto = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
            .withExposedPorts(1883)
            .withCopyToContainer(
                    Transferable.of("listener 1883\nallow_anonymous true\n"),
                    "/mosquitto/config/mosquitto.conf"
            )
            .withCreateContainerCmdModifier(cmd ->
                    cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(MOSQUITTO_PORT), ExposedPort.tcp(1883))
                    )
            );

    private MqttSubscriberNode node;
    private RecordingConnection outConnection;
    private MqttAsyncClient publisher;

    @BeforeEach
    void setUp() throws Exception {
        String brokerUrl = "tcp://" + mosquitto.getHost() + ":" + MOSQUITTO_PORT;

        node = new MqttSubscriberNode("mqtt-1", brokerUrl, "subscriber-test", null, null, TOPIC, new SimpleMeterRegistry());
        outConnection = new RecordingConnection();
        node.getOutputPort("out").connect(outConnection);
        node.initialize();

        publisher = new MqttAsyncClient(brokerUrl, "publisher-test");
        publisher.connect().waitForCompletion();
    }

    @AfterEach
    void tearDown() throws Exception {
        node.shutdown();
        publisher.disconnect();
        publisher.close();
    }

    @Test
    @DisplayName("activate 상태에서는 발행된 메시지를 수신한다")
    void receivesMessageWhenActivated() throws Exception {
        node.activate();

        publish("hello");

        assertThat(waitForMessages(1)).hasSize(1);
    }

    @Test
    @DisplayName("deactivate 상태에서는 발행된 메시지를 수신하지 않는다")
    void doesNotReceiveMessageWhenDeactivated() throws Exception {
        node.activate();
        node.deactivate(); // STANDBY 전환 - 브로커에 실제로 unsubscribe

        publish("should-not-arrive");

        Thread.sleep(2000); // 부재를 증명해야 하므로 고정 시간만큼 대기 후 확인
        assertThat(outConnection.messages()).isEmpty();
    }

    @Test
    @DisplayName("재activate하면 다시 메시지를 수신한다")
    void receivesMessageAfterReactivate() throws Exception {
        node.activate();
        node.deactivate();
        node.activate(); // 재전환 - 구독 복원

        publish("hello-again");

        assertThat(waitForMessages(1)).hasSize(1);
    }

    @Test
    @DisplayName("STANDBY 상태에서 브로커가 재시작돼도 구독이 복원되지 않는다")
    void doesNotResubscribeAfterBrokerRestartWhenDeactivated() throws Exception {
        node.activate();
        node.deactivate(); // STANDBY

        restartBrokerAndReconnectPublisher();

        publish("after-restart-should-not-arrive");

        Thread.sleep(2000);
        assertThat(outConnection.messages()).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE 상태에서 브로커가 재시작되면 구독이 복원되어 계속 수신한다")
    void resubscribesAfterBrokerRestartWhenActivated() throws Exception {
        node.activate();

        restartBrokerAndReconnectPublisher();

        assertThat(publishUntilReceived("after-restart-should-arrive", 1)).hasSize(1);
    }

    @Test
    @DisplayName("initialize 직후 activate 없이 바로 deactivate 해도 메시지를 수신하지 않는다")
    void doesNotReceiveMessageWhenNeverActivated() throws Exception {
        node.deactivate(); // activate() 호출 이력 없이 바로 STANDBY 배정되는 실제 상황 재현

        publish("should-not-arrive-cold-standby");

        Thread.sleep(2000);
        assertThat(outConnection.messages()).isEmpty();
    }

    /**
     * node의 재구독 완료 시점을 테스트에서 직접 관찰할 방법이 없어서,
     * 메시지가 도착할 때까지 주기적으로 재발행하며 기다림 (publisher 재연결과 node 재구독 완료 시점의 경쟁 상태 흡수)
     */
    private List<Message> publishUntilReceived(String payload, int expectedCount) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;

        while (System.currentTimeMillis() < deadline) {
            publish(payload);

            if (outConnection.messages().size() >= expectedCount) {
                return outConnection.messages();
            }

            Thread.sleep(500);
        }

        return outConnection.messages();
    }

    private void publish(String payload) throws Exception {
        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(1);
        publisher.publish(TOPIC, message);
    }

    private List<Message> waitForMessages(int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;

        while (outConnection.messages().size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        return outConnection.messages();
    }

    /**
     * 컨테이너를 실제로 재시작해서 MqttSubscriberNode가 진짜 재연결(connectComplete(reconnect=true, ...))을 타게 만듦
     * publisher는 automaticReconnect를 안 걸어놨으므로, 재시작 전에 명시적으로 끊어두고
     * 브로커 포트가 실제로 다시 응답할 때까지 소켓으로 직접 확인한 뒤 재연결시킴
     */
    private void restartBrokerAndReconnectPublisher() throws Exception {
        publisher.disconnect();

        mosquitto.getDockerClient().restartContainerCmd(mosquitto.getContainerId()).exec();

        // waitForPortOpen은 TCP 포트만 열렸는지 확인함. mosquitto가 진짜 MQTT 핸드셰이크 받을 준비가 됐는지는 확인 안 함
        // CI 환경에서 이 틈이 벌어지면서 publisher.connect()가 Connection lost로 실패함
        // -> 포트 확인 후에, 진짜 MQTT connect() 자체를 재시도하도록 수정하였음
        waitForPortOpen(mosquitto.getHost(), MOSQUITTO_PORT);

        reconnectPublisherWithRetry();
    }

    /**
     * TCP 포트가 열려도 mosquitto가 MQTT 핸드셰이크까지 완전히 준비됐다는 보장은 아니라서
     * (특히 CI처럼 로컬보다 느린 환경) connect() 자체를 재시도해서 그 틈을 흡수
     */
    private void reconnectPublisherWithRetry() throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        Exception lastException = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                publisher.connect().waitForCompletion();
                return;
            } catch (Exception e) {
                lastException = e;
                Thread.sleep(500);
            }
        }

        throw new IllegalStateException("브로커 재시작 후 publisher 재연결 실패", lastException);
    }

    private void waitForPortOpen(String host, int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;

        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 500);
                return; // 연결 성공 - 브로커가 다시 요청을 받을 준비 됨
            } catch (Exception e) {
                Thread.sleep(300);
            }
        }

        throw new IllegalStateException("브로커 재시작 후 포트 응답 대기 시간 초과");
    }

    // 백그라운드 MQTT 콜백 스레드가 쓰고, 메인 테스트 스레드가 읽는 구조라 스레드 안전한 컬렉션 필요
    private static class RecordingConnection implements Connection {

        private final List<Message> received = new CopyOnWriteArrayList<>();

        List<Message> messages() {
            return received;
        }

        @Override
        public String getId() {
            return "";
        }

        @Override
        public void deliver(Message message) {
            received.add(message);
        }

        @Override
        public Message poll() {
            return null;
        }

        @Override
        public int getBufferSize() {
            return received.size();
        }

        @Override
        public void close() {
        }
    }
}