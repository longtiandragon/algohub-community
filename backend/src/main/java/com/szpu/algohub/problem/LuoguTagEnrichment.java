package com.szpu.algohub.problem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformHttpClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Shared problem metadata cache; processes only solved problems with missing metadata. */
@Service
public class LuoguTagEnrichment {
    private final JdbcTemplate jdbc;
    private final PlatformHttpClient http;
    private final TransactionTemplate transaction;
    private final ObjectMapper json = new ObjectMapper();
    private volatile String lastError;
    private volatile boolean running;
    private volatile long retryAfter;
    private Map<Integer,String> algorithms = Map.of();
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "luogu-tags"); t.setDaemon(true); return t;
    });
    @org.springframework.beans.factory.annotation.Value("${algohub.sync.enabled:true}")
    private boolean enabled;
    @jakarta.annotation.PreDestroy
    public void close() { executor.shutdownNow(); }
    public LuoguTagEnrichment(JdbcTemplate jdbc, PlatformHttpClient http, TransactionTemplate transaction) {
        this.jdbc=jdbc; this.http=http; this.transaction=transaction;
    }
    private JsonNode fetch(String path) throws Exception {
        String body=http.get(Platform.LUOGU,"https://www.luogu.com.cn"+path,
            Map.of("x-lentille-request","content-only","Referer","https://www.luogu.com.cn/"),null);
        if (!body.stripLeading().startsWith("{")) throw new IllegalStateException("洛谷返回非 JSON 响应，标签补全稍后重试");
        return json.readTree(body);
    }
    public static List<String> algorithmTags(JsonNode ids, Map<Integer,String> dictionary) {
        if (!ids.isArray()) throw new IllegalArgumentException("题目没有标签数组");
        Set<String> result=new LinkedHashSet<>();
        for(JsonNode id:ids) {
            if(!id.isIntegralNumber()) throw new IllegalArgumentException("标签编号格式变化");
            String label=dictionary.get(id.asInt());
            if(label!=null) result.add(label);
        }
        return List.copyOf(result);
    }
    @Scheduled(fixedDelay=30000, initialDelay=20000)
    public synchronized void schedule() {
        if (!enabled || running || System.currentTimeMillis()<retryAfter) return;
        running=true;
        executor.submit(() -> { try { enrich(); } finally { running=false; } });
    }
    public void enrich() {
        if(System.currentTimeMillis()<retryAfter) return;
        var pending=jdbc.queryForList("""
            select p.id,p.problem_key from problem p where p.platform='LUOGU' and not p.tags_complete
            and exists(select 1 from user_solved_problem s where s.problem_id=p.id)
            order by p.updated_at,p.id limit 20
            """);
        if(pending.isEmpty()) return;
        running=true;
        try {
            if(algorithms.isEmpty()) {
                JsonNode tags=fetch("/_lfe/tags").path("tags");
                if(!tags.isArray()) throw new IllegalStateException("标签字典格式变化");
                Map<Integer,String> map=new HashMap<>();
                for(JsonNode t:tags) if(t.path("type").asInt()==2 && t.path("name").isTextual()) map.put(t.path("id").asInt(),t.path("name").asText());
                if(map.isEmpty()) throw new IllegalStateException("标签字典没有算法标签");
                algorithms=Map.copyOf(map);
            }
            for(var row:pending) {
                String pid=(String)row.get("problem_key");
                JsonNode p=fetch("/problem/"+pid).path("data").path("problem");
                if(!pid.equals(p.path("pid").asText())) throw new IllegalStateException("题目身份不匹配");
                List<String> tags=algorithmTags(p.path("tags"),algorithms);
                transaction.executeWithoutResult(status -> {
                    for(String tag:tags) jdbc.update("insert into problem_tag(problem_id,tag_raw,tag_source) values (?,?,'OFFICIAL') on conflict(problem_id,tag_raw) do nothing",row.get("id"),tag);
                    jdbc.update("update problem set tags_complete=true,updated_at=now() where id=?",row.get("id"));
                });
            }
            lastError=null;
        } catch(Exception e) {
            // Do not expose response bodies or credentials. Back off the whole batch on source errors.
            lastError="洛谷标签补全暂时失败，已保留缓存，将在 5 分钟后重试";
            retryAfter=System.currentTimeMillis()+300000;
        } finally { running=false; }
    }
    public Map<String,Object> progress(Long userId) {
        var result=new LinkedHashMap<String,Object>(jdbc.queryForMap("""
            select count(distinct p.id) as total,count(distinct p.id) filter(where p.tags_complete) as completed
            from user_solved_problem s join problem p on p.id=s.problem_id
            where s.user_id=? and s.platform='LUOGU'
            """,userId));
        result.put("running",running); result.put("message",lastError==null ? "后台分批补全算法标签，已完成的题目使用缓存" : lastError);
        return result;
    }
}
