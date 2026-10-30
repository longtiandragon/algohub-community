package com.szpu.algohub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;

/**
 * 两个独立数据库事务实际竞争管理员行锁。仅应与其他集成测试串行运行在独立测试库中。
 * 需要提交夹具让两个线程看见；原管理员权限精确暂存并在 finally 恢复，无全表删除。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountDeletionConcurrencyTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserMapper users;
    @Autowired AuthService auth;
    @Autowired SecurityActionService actions;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactionManager;

    record AdminBackup(long id, String permissions) {}
    record Fixture(AppUser first, AppUser second, List<AdminBackup> backups) {}

    TransactionTemplate transactions() { return new TransactionTemplate(transactionManager); }

    Fixture fixture() {
        return transactions().execute(status -> {
            db.queryForList("select id from app_user where role='ADMIN' order by id for update");
            List<AdminBackup> backups = db.query("""
                    select id,array_to_string(admin_permissions,',') as permissions from app_user where role='ADMIN'
                    """, (rs, row) -> new AdminBackup(rs.getLong("id"), rs.getString("permissions")));
            db.update("update app_user set admin_permissions=array['CONTENT']::varchar(32)[] where role='ADMIN'");
            return new Fixture(newKeeper(), newKeeper(), backups);
        });
    }

    AppUser newKeeper() {
        String name = "keeper-race-" + UUID.randomUUID().toString().replace("-", "");
        long id = db.queryForObject("""
                insert into app_user(username,display_name,role,admin_permissions)
                values(?,?,'ADMIN',array['ADMINS']::varchar(32)[]) returning id
                """, Long.class, name, name);
        return users.findById(id);
    }

    void cleanup(Fixture fixture) {
        transactions().executeWithoutResult(status -> {
            db.queryForList("select id from app_user where role='ADMIN' order by id for update");
            db.update("delete from admin_audit_log where actor_id in (?,?)", fixture.first().getId(), fixture.second().getId());
            db.update("delete from auth_event where username in (?,?)", fixture.first().getUsername(), fixture.second().getUsername());
            db.update("delete from app_user where id in (?,?)", fixture.first().getId(), fixture.second().getId());
            for (AdminBackup backup : fixture.backups()) {
                db.update("""
                        update app_user set admin_permissions=case when ?::text is null then null
                          else string_to_array(?::text,',')::varchar(32)[] end where id=?
                        """, backup.permissions(), backup.permissions(), backup.id());
            }
        });
    }

    @Test void simultaneousKeeperDeletionsCannotRemoveBoth() throws Exception {
        Fixture fixture = fixture();
        try {
            List<Integer> statuses = compete(fixture, false,
                    () -> deleteDirectly(fixture.first()), () -> deleteDirectly(fixture.second()));
            assertThat(statuses).containsExactly(200, 409);
            assertThat(activeKeeperCount()).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from app_user where id=?", Integer.class, fixture.second().getId()))
                    .isEqualTo(1);
        } finally { cleanup(fixture); }
    }

    @Test void adminRoleChangeAndVerifiedSelfDeletionUseTheSameLockOrder() throws Exception {
        Fixture fixture = fixture();
        String proof = null;
        try {
            proof = actions.grant(fixture.second(), "DELETE_ACCOUNT");
            final String actionToken = proof;
            List<Integer> statuses = compete(fixture, true,
                    () -> perform(post("/api/admin/admins/" + fixture.first().getId())
                            .with(user(new LoginUser(fixture.second()))).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"STUDENT\"}")),
                    () -> perform(post("/api/me/delete").with(user(new LoginUser(fixture.second())))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"confirmUsername\":\""
                                    + fixture.second().getUsername() + "\",\"actionToken\":\"" + actionToken + "\"}")));
            assertThat(statuses).containsExactly(200, 409);
            assertThat(activeKeeperCount()).isEqualTo(1);
            assertThat(db.queryForObject("select role from app_user where id=?", String.class, fixture.first().getId()))
                    .isEqualTo("STUDENT");
            assertThat(db.queryForObject("select role from app_user where id=?", String.class, fixture.second().getId()))
                    .isEqualTo("ADMIN");
        } finally {
            if (proof != null) redis.delete("algohub:security:proof:" + proof);
            cleanup(fixture);
        }
    }

    int deleteDirectly(AppUser target) {
        try {
            auth.deleteVerifiedAccount(target.getId(), target.getUsername(), "test", "JUnit");
            return 200;
        } catch (ApiException error) {
            if (error.getKind() == ErrorKind.CONFLICT) return 409;
            throw error;
        }
    }

    int perform(MockHttpServletRequestBuilder request) {
        try { return mvc.perform(request).andReturn().getResponse().getStatus(); }
        catch (Exception error) { throw new IllegalStateException("并发 API 调用失败", error); }
    }

    int activeKeeperCount() {
        return db.queryForObject("""
                select count(*) from app_user where role='ADMIN' and status='ACTIVE'
                  and (admin_permissions is null or 'ADMINS'=any(admin_permissions))
                """, Integer.class);
    }

    /**
     * prefixOnly 模拟管理端有序加锁已取得前半段、尚未锁到注销人的时刻。
     * 若注销先锁本人再锁全部管理员，管理端继续加锁时就会与它形成真实死锁。
     */
    List<Integer> compete(Fixture fixture, boolean prefixOnly, IntSupplier leader, IntSupplier follower) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String followerName = "keeper-wait-" + UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> transactions().execute(status -> {
                db.execute("set local lock_timeout='10s'");
                if (prefixOnly) {
                    db.queryForList("select id from app_user where role='ADMIN' and id<? order by id for update",
                            fixture.second().getId());
                } else {
                    db.queryForList("select id from app_user where role='ADMIN' order by id for update");
                }
                held.countDown();
                await(release);
                int response = leader.getAsInt();
                if (response >= 400) status.setRollbackOnly();
                return response;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> transactions().execute(status -> {
                db.execute("set local lock_timeout='10s'");
                db.queryForObject("select set_config('application_name',?,true)", String.class, followerName);
                int response = follower.getAsInt();
                if (response >= 400) status.setRollbackOnly();
                return response;
            }));
            // 检查数据库确实发生行锁等待，避免只启动了两个线程却顺序执行而得到假阳性。
            awaitDatabaseLock(followerName);
            release.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("等待并发夹具超时");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    void awaitDatabaseLock(String applicationName) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(db.queryForObject("""
                    select exists(select 1 from pg_stat_activity where application_name=? and wait_event_type='Lock')
                    """, Boolean.class, applicationName))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("未观测到预期的 PostgreSQL 行锁等待");
    }
}
