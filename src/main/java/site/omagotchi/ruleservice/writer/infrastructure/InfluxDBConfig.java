package site.omagotchi.ruleservice.writer.infrastructure;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfluxDbConfig {
    /**influxDB 클라이언트 빈 등록*/
    @Bean(destroyMethod = "close") // 앱 종료시 같이 종료됨.
    public InfluxDBClient influxDBClient(InfluxDbProperties influxDBProperties){
        return InfluxDBClientFactory.create(
                influxDBProperties.url(),
                influxDBProperties.token().toCharArray(),
                influxDBProperties.org()
        );
    }
}
