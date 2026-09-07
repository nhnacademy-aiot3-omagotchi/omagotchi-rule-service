package site.omagotchi.ruleservice.global.config;

import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.micrometer.RabbitListenerObservationConvention;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageReceiverContext;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageSenderContext;
import org.springframework.amqp.rabbit.support.micrometer.RabbitTemplateObservationConvention;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Rabbit 계측 속성")
class RabbitObservationConfigTest {

    @Test
    @DisplayName("발행·소비 이름과 속성에서 센서 Routing Key 제외")
    void omitsRoutingKeyFromNamesAndAttributes() {
        // Given
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue("sensor-events");
        properties.setReceivedRoutingKey("sensor.private-device-id");
        Message message = new Message(new byte[0], properties);
        var sender = new RabbitMessageSenderContext(message, "rabbitTemplate", "sensor", "sensor.private-device-id");
        var receiver = new RabbitMessageReceiverContext(message, "sensor-listener");
        var config = new RabbitObservationConfig();

        // When
        var publish = config.rabbitTemplateObservationConvention();
        var consume = config.rabbitListenerObservationConvention();

        // Then
        assertThat(publish.getContextualName(sender)).isEqualTo("rabbitmq publish");
        assertThat(consume.getContextualName(receiver)).isEqualTo("rabbitmq consume");
        assertThat(publish.getLowCardinalityKeyValues(sender)).containsExactlyInAnyOrder(
                KeyValue.of("messaging.system", "rabbitmq"),
                KeyValue.of("messaging.operation.type", "send"),
                KeyValue.of("messaging.destination.name", "sensor"));
        assertThat(consume.getLowCardinalityKeyValues(receiver)).containsExactlyInAnyOrder(
                KeyValue.of("messaging.system", "rabbitmq"),
                KeyValue.of("messaging.operation.type", "process"),
                KeyValue.of("messaging.destination.name", "sensor-events"));
        assertThat(publish.getHighCardinalityKeyValues(sender)).isEmpty();
        assertThat(consume.getHighCardinalityKeyValues(receiver)).isEmpty();
    }

    @Test
    @DisplayName("실제 설정의 발행·소비 계측 활성화와 공통 Convention 연결")
    void wiresConventionsToTemplateAndListener() {
        // Given: 실제 YAML과 Bean 구성, Broker 연결만 대체
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=test")
                .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
                .withUserConfiguration(RabbitObservationConfig.class, RabbitTopologyConfig.class)
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .run(context -> {
                    // When: Listener 시작 없이 Factory의 Container 설정 확인
                    assertThat(context).hasNotFailed();
                    RabbitTemplate template = context.getBean(RabbitTemplate.class);
                    var container = context.getBean(SimpleRabbitListenerContainerFactory.class)
                            .createListenerContainer();

                    // Then: 공개 Getter가 없는 Framework 설정값 확인, 실제 메시지 전송 제외
                    assertThat(template)
                            .hasFieldOrPropertyWithValue("observationEnabled", true)
                            .hasFieldOrPropertyWithValue("observationConvention",
                                    context.getBean(RabbitTemplateObservationConvention.class));
                    assertThat(container)
                            .hasFieldOrPropertyWithValue("observationEnabled", true)
                            .hasFieldOrPropertyWithValue("observationConvention",
                                    context.getBean(RabbitListenerObservationConvention.class));
                });
    }
}
