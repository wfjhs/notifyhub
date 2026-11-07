package com.notifyhub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 通知中台启动类。
 * <p>
 * {@code @EnableScheduling} 是必需的：可靠投递不依赖 MQ，
 * 而是靠定时任务扫描「未达终态」的消息做补偿。
 */
@EnableScheduling
@MapperScan("com.notifyhub.mapper")
@SpringBootApplication
public class NotifyHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyHubApplication.class, args);
    }
}
