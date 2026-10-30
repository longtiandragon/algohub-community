package com.szpu.algohub.account;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.statistics.SolvedMapper;
import com.szpu.algohub.statistics.SubmissionMapper;
import com.szpu.algohub.sync.SyncService;

/** 用户在QOJ浏览器内主动读取本人 AC 列表；站点只收精简字段，不接收会话或页面响应。 */
@Service
public class QojBrowserImportService {
    @org.springframework.beans.factory.annotation.Value("${algohub.qoj.server-enabled:false}")
    private boolean requireMottoVerification;
    private static final SecureRandom RANDOM = new SecureRandom();
    public record BrowserRecord(String id, String uid, String pid, String title,
                                Integer difficulty, Long submitTime, String language) {}
    public record Page(int page, int total, int perPage, List<BrowserRecord> rows) {}
    public record Progress(int received, int total, boolean complete, boolean autoEnabled) {}

    private final JdbcTemplate jdbc;
    private final SubmissionMapper submissions;
    private final SolvedMapper solved;
    private final SyncService sync;
    private final PlatformAccountMapper accounts;

    public QojBrowserImportService(JdbcTemplate jdbc,
            SubmissionMapper submissions, SolvedMapper solved,
            SyncService sync, PlatformAccountMapper accounts) {
        this.jdbc = jdbc;
        this.submissions = submissions;
        this.solved = solved;
        this.sync = sync;
        this.accounts = accounts;
    }

    private PlatformAccount owned(Long userId, Long accountId) {
        PlatformAccount account = accounts.findById(accountId);
        if (requireMottoVerification && account != null && !"VERIFIED".equals(account.getOwnershipStatus()))
            throw ApiException.forbidden("请先完成 QOJ Motto 归属验证，再连接插件同步");
        if (account == null || !userId.equals(account.getUserId())
                || !"QOJ".equals(account.getPlatform())
                || !("VERIFIED".equals(account.getOwnershipStatus())
                    || ("BROWSER".equals(account.getAuthMode()) && account.getOwnershipNote() == null
                        && List.of("UNVERIFIED", "RESOLVED").contains(account.getOwnershipStatus())))
                || account.getHandle() == null || !account.getHandle().matches("[A-Za-z0-9_]{1,64}")) {
            throw ApiException.validation("请先添加本人 QOJ 用户名，再使用浏览器同步");
        }
        // Browser tickets bypass login authentication, so recheck disabled users here.
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from app_user where id=? and status='ACTIVE')", Boolean.class, userId))) {
            throw ApiException.forbidden("平台账号已停用，不能使用浏览器同步");
        }
        return account;
    }

    /** 与同步保存、解绑一致，先锁账号再锁授权/导入状态，等待后重新校验归属。 */
    private PlatformAccount lockOwned(Long userId, Long accountId) {
        if (jdbc.queryForList("select id from platform_account where id=? for update", Long.class, accountId).isEmpty()) {
            throw ApiException.forbidden("QOJ账号已解绑");
        }
        return owned(userId, accountId);
    }

    @Transactional
    public Map<String, String> begin(Long userId, Long accountId) {
        return begin(userId, accountId, false);
    }

    @Transactional
    public Map<String, String> begin(Long userId, Long accountId, boolean auto) {
        PlatformAccount account = lockOwned(userId, accountId);
        Map<String, String> result = start(account);
        if (auto) {
            jdbc.update("""
                    insert into qoj_auto_sync_grant(account_id,token_hash,expires_at)
                    values (?,?,now()+interval '90 days')
                    on conflict(account_id) do update set token_hash=excluded.token_hash,
                        expires_at=excluded.expires_at,last_used_at=null,created_at=now()
                    """, accountId, hash(result.get("ticket")));
        } else {
            jdbc.update("delete from qoj_auto_sync_grant where account_id=?", accountId);
        }
        return result;
    }

    private Map<String, String> start(PlatformAccount account) {
        Long accountId = account.getId();
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String ticket = account.getHandle() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.update("""
                insert into qoj_browser_import(account_id,token_hash,expires_at)
                values (?,?,now()+interval '60 minutes')
                on conflict(account_id) do update set next_page=1,total=null,per_page=null,page_hashes='{}',
                    token_hash=excluded.token_hash,expires_at=excluded.expires_at,started_at=now()
                """, accountId, hash(ticket));
        jdbc.update("delete from qoj_browser_import_record where account_id=?", accountId);
        return Map.of("uid", account.getHandle(), "ticket", ticket);
    }

    public Map<String, Object> autoStatus(Long userId, Long accountId) {
        owned(userId, accountId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select expires_at as \"expiresAt\" from qoj_auto_sync_grant where account_id=? and expires_at>now()", accountId);
        return rows.isEmpty() ? Map.of("enabled", false) : Map.of("enabled", true, "expiresAt", rows.getFirst().get("expiresAt"));
    }

    @Transactional
    public void revokeAuto(Long userId, Long accountId) {
        lockOwned(userId, accountId);
        jdbc.update("delete from qoj_auto_sync_grant where account_id=?", accountId);
        // Also invalidate tickets already issued by this grant; completed history is separate.
        jdbc.update("delete from qoj_browser_import where account_id=?", accountId);
    }

    @Transactional
    public Map<String, String> autoBegin(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_]{1,64}\\.[A-Za-z0-9_-]{43}"))
            throw ApiException.forbidden("自动同步授权无效或已过期，请重新连接");
        List<Long> candidates = jdbc.queryForList("""
                select account_id from qoj_auto_sync_grant where token_hash=? and expires_at>now()
                """, Long.class, hash(token));
        if (candidates.size() != 1) throw ApiException.forbidden("自动同步授权无效或已过期，请重新连接");
        Long accountId = candidates.getFirst();
        if (jdbc.queryForList("select id from platform_account where id=? for update", Long.class, accountId).isEmpty()) {
            throw ApiException.forbidden("QOJ账号已解绑");
        }
        PlatformAccount account = accounts.findById(accountId);
        // 候选查询只定位账号；取得账号锁后再核验授权，撤销/轮换后的旧令牌不能开始新会话。
        List<Long> accountsWithGrant = jdbc.queryForList("""
                update qoj_auto_sync_grant set last_used_at=now()
                where account_id=? and token_hash=? and expires_at>now()
                  and (last_used_at is null or last_used_at<now()-interval '5 minutes')
                returning account_id
                """, Long.class, accountId, hash(token));
        if (accountsWithGrant.size() != 1) throw ApiException.forbidden("自动同步授权无效、已过期或请求过于频繁");
        if (account == null) throw ApiException.forbidden("QOJ账号已解绑");
        return start(owned(account.getUserId(), accountId));
    }

    private static String hash(String ticket) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Transactional
    public Progress pageWithTicket(String ticket, Page page) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_]{1,64}\\.[A-Za-z0-9_-]{43}"))
            throw ApiException.forbidden("QOJ同步码无效或已过期");
        List<Map<String, Object>> grants = jdbc.queryForList("""
                select i.account_id, a.user_id from qoj_browser_import i
                join platform_account a on a.id=i.account_id
                where i.token_hash=? and i.expires_at>now()
                """, hash(ticket));
        if (grants.size() != 1) throw ApiException.forbidden("QOJ同步码无效或已过期");
        Map<String, Object> grant = grants.getFirst();
        Long accountId = ((Number) grant.get("account_id")).longValue();
        PlatformAccount account = lockOwned(((Number) grant.get("user_id")).longValue(), accountId);
        List<Map<String, Object>> states = jdbc.queryForList("""
                select * from qoj_browser_import
                 where account_id=? and token_hash=? and expires_at>now() for update
                """, accountId, hash(ticket));
        if (states.size() != 1) throw ApiException.forbidden("QOJ同步码无效或已过期");
        boolean auto = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from qoj_auto_sync_grant
                              where account_id=? and token_hash=? and expires_at>now())
                """, Boolean.class, accountId, hash(ticket)));
        Progress result = page(account, states.getFirst(), page);
        return new Progress(result.received(), result.total(), result.complete(), auto);
    }

    @Transactional
    public Progress page(Long userId, Long accountId, Page page) {
        PlatformAccount account = lockOwned(userId, accountId);
        List<Map<String, Object>> states = jdbc.queryForList(
                "select * from qoj_browser_import where account_id=? and expires_at>now() for update", accountId);
        if (states.size() != 1) throw ApiException.forbidden("QOJ同步码无效或已过期");
        return page(account, states.getFirst(), page);
    }

    private Progress page(PlatformAccount account, Map<String, Object> state, Page page) {
        Long accountId = account.getId();
        int expected = ((Number) state.get("next_page")).intValue();
        if (page == null || page.page() < 1 || page.page() > expected || page.total() < 0 || page.total() > 20000
                || page.perPage() < 1 || page.perPage() > 1000 || page.rows() == null
                || page.rows().size() != Math.min(page.perPage(), Math.max(0, page.total() - (page.page() - 1) * page.perPage()))
                || page.page() > 500 || page.page() > Math.max(1, (page.total() + page.perPage() - 1) / page.perPage())) {
            throw ApiException.validation("QOJ同步分页不完整，请从第一页重新开始");
        }
        if (state.get("total") != null && (((Number) state.get("total")).intValue() != page.total()
                || ((Number) state.get("per_page")).intValue() != page.perPage())) {
            throw ApiException.validation("同步期间QOJ记录数量变化，请重新开始");
        }
        String digest = BrowserImportPageHash.of(page);
        if (page.page() < expected) {
            String previous = jdbc.queryForObject(
                    "select page_hashes ->> ? from qoj_browser_import where account_id=?",
                    String.class, String.valueOf(page.page()), accountId);
            if (!digest.equals(previous)) throw ApiException.validation("重传分页与已接收内容不一致，请重新开始");
            return new Progress(Math.min(page.total(), page.page() * page.perPage()), page.total(),
                    (long) page.page() * page.perPage() >= page.total(), false);
        }
        Instant now = Instant.now();
        var batch = new java.util.ArrayList<Object[]>();
        for (BrowserRecord row : page.rows()) {
            if (row == null || row.id() == null || !row.id().matches("[1-9][0-9]{0,17}")
                    || !account.getHandle().equals(row.uid())
                    || row.pid() == null || !row.pid().matches("[1-9][0-9]{0,17}")
                    || row.submitTime() == null || row.submitTime() < 1_000_000_000L
                    || row.submitTime() > now.plusSeconds(300).getEpochSecond()
                    || (row.difficulty() != null && (row.difficulty() < 0 || row.difficulty() > 8))
                    || (row.title() != null && row.title().length() > 200)
                    || (row.language() != null && row.language().length() > 40)) {
                throw ApiException.validation("QOJ评测字段或 UID 异常，未导入这一页");
            }
            batch.add(new Object[] {accountId, Long.parseLong(row.id()), row.pid(), row.title(), row.difficulty(),
                    Timestamp.from(Instant.ofEpochSecond(row.submitTime())), row.language()});
        }
        int[] inserted = jdbc.batchUpdate("""
                insert into qoj_browser_import_record
                (account_id,record_id,problem_key,title,difficulty,submitted_at,language)
                values (?,?,?,?,?,?,?) on conflict do nothing
                """, batch);
        for (int count : inserted) {
            if (count != 1) throw ApiException.validation("评测记录重复或暂存失败，未导入这一页");
        }
        boolean complete = (long) page.page() * page.perPage() >= page.total();
        jdbc.update("update qoj_browser_import set next_page=?,total=?,per_page=? where account_id=?",
                page.page() + 1, page.total(), page.perPage(), accountId);
        jdbc.update("update qoj_browser_import set page_hashes=page_hashes || jsonb_build_object(cast(? as text),cast(? as text)) where account_id=?",
                String.valueOf(page.page()), digest, accountId);
        if (complete) finish(account);
        return new Progress(Math.min(page.total(), page.page() * page.perPage()), page.total(), complete, false);
    }

    private void finish(PlatformAccount account) {
        Long accountId = account.getId();
        int total = jdbc.queryForObject("select total from qoj_browser_import where account_id=?", Integer.class, accountId);
        int staged = jdbc.queryForObject("select count(*) from qoj_browser_import_record where account_id=?", Integer.class, accountId);
        if (staged != total) throw ApiException.validation("QOJ历史未完整读取，未更新排行");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from qoj_browser_import_record where account_id=? order by submitted_at", accountId);
        // Stable key order prevents concurrent imports from locking new catalog entries in opposite order.
        rows.sort(java.util.Comparator.comparing(row -> (String) row.get("problem_key")));
        for (Map<String, Object> row : rows) {
            String pid = (String) row.get("problem_key");
            List<Long> problems = jdbc.queryForList(
                    "select id from problem where platform='QOJ' and problem_key=?", Long.class, pid);
            if (problems.isEmpty()) {
                String difficulty = row.get("difficulty") == null ? null : row.get("difficulty").toString();
                // Only create missing entries; never overwrite independently collected official metadata.
                jdbc.update("""
                        insert into problem(platform,problem_key,title,url,difficulty_raw,difficulty_label,difficulty_source)
                        values ('QOJ',?,?,?,?,?,'USER_REPORTED') on conflict(platform,problem_key) do nothing
                        """, pid, row.get("title"), "https://qoj.ac/problem/" + pid,
                        difficulty, null);
                problems = jdbc.queryForList(
                        "select id from problem where platform='QOJ' and problem_key=?", Long.class, pid);
            }
            Long problemId = problems.getFirst();
            Instant at = ((Timestamp) row.get("submitted_at")).toInstant();
            submissions.insertQojBrowserRecord(accountId, problemId, row.get("record_id").toString(),
                    at, (String) row.get("language"));
            // Insert the solve without changing a trusted existing time, then fill browser time if eligible.
            solved.upsert(account.getUserId(), accountId, problemId, "QOJ", null, "UNKNOWN", "BROWSER_SYNC");
            solved.recordBrowserTime(accountId, problemId, at);
        }
        sync.rebuildSubmissionStats(accountId);
        // 完整读取客户端分页不等于完整、可信的源站历史；保留现有服务端状态。
        accounts.markSnapshotSuccess(accountId, false);
        jdbc.update("delete from qoj_browser_import_record where account_id=?", accountId);
    }
}
