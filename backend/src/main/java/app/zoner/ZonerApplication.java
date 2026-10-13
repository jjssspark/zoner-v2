package app.zoner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ZonerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZonerApplication.class, args);
    }
}
