package site.omagotchi.ruleservice.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageReceiverContext;
import org.springframework.amqp.rabbit.support.micrometer.RabbitMessageSenderContext;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(publish.getLowCardinalityKeyValues(sender).toString())
                .contains("messaging.system", "rabbitmq", "sensor").doesNotContain("private-device-id");
        assertThat(consume.getLowCardinalityKeyValues(receiver).toString())
                .contains("messaging.destination.name", "sensor-events").doesNotContain("private-device-id");
    }
}
