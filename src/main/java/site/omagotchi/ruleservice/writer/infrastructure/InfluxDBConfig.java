package site.omagotchi.ruleservice.writer.infrastructure;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfluxDBConfig {

    @Bean(destroyMethod = "close")
    public InfluxDBClient influxDBClient(InfluxDBProperties influxDBProperties){
        return InfluxDBClientFactory.create(
                influxDBProperties.url(),
                influxDBProperties.token().toCharArray(),
                influxDBProperties.org()
        );
    }
}
