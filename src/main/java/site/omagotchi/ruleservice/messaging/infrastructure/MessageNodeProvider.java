package site.omagotchi.ruleservice.messaging.infrastructure;

import site.omagotchi.ruleservice.messaging.domain.PublishMode;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import site.omagotchi.ruleservice.flow.domain.registry.NodeDescriptor;
import site.omagotchi.ruleservice.flow.domain.registry.NodeProvider;
import site.omagotchi.ruleservice.messaging.infrastructure.PublishRetryBuffer;
import site.omagotchi.ruleservice.messaging.infrastructure.RabbitTopologyConfig;

import java.util.List;

@RequiredArgsConstructor
@Component
public class MessageNodeProvider implements NodeProvider {
    private final RabbitTemplate rabbitTemplate;
    private final PublishRetryBuffer publishRetryBuffer;

    @Override
    public List<NodeDescriptor> provide() {
        return List.of(
                new NodeDescriptor(
                        "RabbitPublisher",
                        "raw,quality 메시지 발행",
                        config -> {
                            String id = (String) config.get("id");
                            PublishMode mode = resolveMode(config.get("mode"));
                            return new RabbitPublisherNode(
                                    id,
                                    rabbitTemplate,
                                    RabbitTopologyConfig.EXCHANGE_MAIN,
                                    mode,
                                    publishRetryBuffer
                            );
                        })
        );
    }

    private PublishMode resolveMode(Object mode){
        if(mode == null){
            throw new IllegalArgumentException("config에 mode가 null입니다.");
        }

        return PublishMode.valueOf(mode.toString().trim().toUpperCase());
    }
}
