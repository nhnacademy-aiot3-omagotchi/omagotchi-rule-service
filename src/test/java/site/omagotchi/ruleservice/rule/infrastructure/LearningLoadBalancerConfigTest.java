package site.omagotchi.ruleservice.rule.infrastructure;

import com.netflix.appinfo.InstanceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.netflix.eureka.EurekaServiceInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LearningLoadBalancerConfigTest {

    @Test
    @DisplayName("Rule 피어용 전체 목록에서 Learning UP 인스턴스만 호출 대상으로 선택")
    void excludesUnavailableLearningInstance() {
        // Given
        DiscoveryClient discovery = mock(DiscoveryClient.class);
        EurekaServiceInstance up = new EurekaServiceInstance(InstanceInfo.Builder.newBuilder()
                .setInstanceId("learning-a").setAppName("learning-service")
                .setStatus(InstanceInfo.InstanceStatus.UP).build());
        EurekaServiceInstance excluded = new EurekaServiceInstance(InstanceInfo.Builder.newBuilder()
                .setInstanceId("learning-b").setAppName("learning-service")
                .setStatus(InstanceInfo.InstanceStatus.OUT_OF_SERVICE).build());
        when(discovery.getInstances("learning-service")).thenReturn(List.of(up, excluded));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    LoadBalancerClientFactory.PROPERTY_NAME, "learning-service")));
            context.registerBean(DiscoveryClient.class, () -> discovery);
            context.refresh();

            // When
            ServiceInstanceListSupplier supplier = new LearningLoadBalancerConfig().learningServiceInstanceListSupplier(context);
            List<ServiceInstance> instances = supplier.get().blockFirst(Duration.ofSeconds(3));

            // Then
            assertThat(instances).containsExactly(up);
        }
    }
}
