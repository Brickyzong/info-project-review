package com.xmps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan(basePackages = "com.xmps")
public class XmpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(XmpsApplication.class, args);
    }
}
