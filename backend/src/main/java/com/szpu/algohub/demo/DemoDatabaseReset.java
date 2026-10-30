package com.szpu.algohub.demo;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 示例库每次启动都从空库开始：先清空再按迁移脚本重建，示例数据随后由 DemoDataSeeder 写入。
 * 清库只认库名以 _demo 结尾的数据库，配置写错连到开发库或测试库时直接拒绝启动。
 */
@Configuration
@Profile("demo & !prod")
public class DemoDatabaseReset {

    @Bean
    FlywayMigrationStrategy demoCleanMigrate() {
        return flyway -> {
            String database = databaseName(flyway.getConfiguration().getDataSource());
            if (database == null || !database.endsWith("_demo")) {
                throw new IllegalStateException("demo 配置只能连接库名以 _demo 结尾的数据库，当前是 " + database
                        + "，为避免清掉真实数据已停止启动");
            }
            flyway.clean();
            flyway.migrate();
        };
    }

    private static String databaseName(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getCatalog();
        } catch (SQLException e) {
            throw new IllegalStateException("无法确认示例库名称", e);
        }
    }
}
