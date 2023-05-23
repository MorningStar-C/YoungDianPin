package com.ydp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.ydp.mapper")
@SpringBootApplication
public class YoungDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(YoungDianPingApplication.class, args);
    }

}
