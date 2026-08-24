package site.omagotchi.ruleservice.messaging.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import site.omagotchi.ruleservice.global.security.TestJwtKeyConfig;

import java.util.Map;


import static org.junit.jupiter.api.Assertions.*;
/** 실제 환경을 testContainer로 확인 (통합 x)*/
@Slf4j
@Import(TestJwtKeyConfig.class)
@ActiveProfiles("test")
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class RabbitTopologyConfigTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Test
    @DisplayName("뷰 확인용 출력된 url을 확인해서 뷰에서 토폴로지 생성 확인 가능")
    void viewTest() throws InterruptedException{
        log.info("관리 UI: " + rabbitMQContainer.getHttpUrl());
        log.info("id: guest, password: guest");

        // 뷰 확인 필요시 아래 주석 해제.
        // 테스트 코드가 종료되면 컨테이너도 내려가기때문에 일시중단 기능

        // Thread.sleep(600_000);
    }

    @Test
    @DisplayName("익스체인지 생성 확인")
    void generateExchangeTest(){
        assertDoesNotThrow(
                () -> rabbitTemplate.execute( channel ->{
                        channel.exchangeDeclarePassive(RabbitTopologyConfig.EXCHANGE_MAIN);
                        channel.exchangeDeclarePassive(RabbitTopologyConfig.EXCHANGE_RAW_DEAD_LETTER);
                        return null;
                    })
        );
    }

    @Test
    @DisplayName("큐 생성 확인")
    void generateQueueTest(){
        assertAll(
                () -> assertNotNull(amqpAdmin.getQueueProperties(RabbitTopologyConfig.QUEUE_RAW)),
                () -> assertNotNull(amqpAdmin.getQueueProperties(RabbitTopologyConfig.QUEUE_QUALITY)),
                () -> assertNotNull(amqpAdmin.getQueueProperties(RabbitTopologyConfig.QUEUE_RAW_DEAD_LETTER))
        );
    }

    @Test
    @DisplayName("바인딩 테스트 - 토픽에따라 큐에 저장되는지 확인")
    void bindingTest() {
        Map<String, Object> testMessage = Map.of("value", 30);

        //raw 메세지 검증
        rabbitTemplate.convertAndSend(RabbitTopologyConfig.EXCHANGE_MAIN, "raw.temperature", testMessage);
        Object receivedRaw = rabbitTemplate.receiveAndConvert(RabbitTopologyConfig.QUEUE_RAW, 2000);

        assertNotNull(receivedRaw);
        assertEquals(testMessage, receivedRaw);

        //quality 메세지 검증
        rabbitTemplate.convertAndSend(RabbitTopologyConfig.EXCHANGE_MAIN, "quality.temperature", testMessage);
        Object receivedQuality = rabbitTemplate.receiveAndConvert(RabbitTopologyConfig.QUEUE_QUALITY, 2000);

        assertNotNull(receivedQuality);
        assertEquals(testMessage, receivedQuality);

    }

}
