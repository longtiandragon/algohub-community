package com.szpu.algohub;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.szpu.algohub.config.AppProperties;

/**
 * 本地运行的算法学习平台后端。仅监听回环地址，由本机浏览器访问。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
@MapperScan(basePackages = "com.szpu.algohub", annotationClass = Mapper.class)
public class AlgoHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlgoHubApplication.class, args);
    }
}
