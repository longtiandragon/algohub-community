package com.szpu.algohub.statistics;

import java.time.LocalDate;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.common.*;
import com.szpu.algohub.platform.Platform;

/** 本人的记录和经过档案可见性校验的公开已通过记录。 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {
    private final HistoryMapper mapper;
    private final AuthService authService;
    public HistoryController(HistoryMapper mapper, AuthService authService) { this.mapper=mapper; this.authService=authService; }
    @GetMapping("/solved")
    public ApiResult<List<Map<String,Object>>> solved(@RequestParam String platform) {
        var rows = mapper.solvedRows(CurrentUser.requireId(), Platform.parse(platform).name());
        if (rows.size()>100000) throw ApiException.validation("题单超过单次导出上限");
        return ApiResult.ok(rows);
    }
    private record Range(Instant from, Instant until) {}
    private Range range(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) throw ApiException.validation("开始日期不能晚于结束日期");
        if ((from != null && (from.getYear()<1970 || from.getYear()>9998)) ||
            (to != null && (to.getYear()<1970 || to.getYear()>9998))) throw ApiException.validation("日期超出支持范围");
        return new Range(from==null ? null : from.atStartOfDay(TimeSupport.CAMPUS_ZONE).toInstant(),
                         to==null ? null : to.plusDays(1).atStartOfDay(TimeSupport.CAMPUS_ZONE).toInstant());
    }
    private String platform(String value) { return value==null || value.isBlank() ? null : Platform.parse(value).name(); }

    @GetMapping
    public ApiResult<Map<String,Object>> list(@RequestParam(required=false) String platform,
        @RequestParam(required=false) Boolean accepted,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="30") int size) {
        if(page<1 || size<1 || size>100) throw ApiException.validation("页码必须为正，单页条数为 1～100");
        Range r=range(from,to); String p=platform(platform); Long user=CurrentUser.requireId();
        return ApiResult.ok(Map.of("rows",mapper.rows(user,p,accepted,r.from,r.until,size,((long)page-1)*size),
            "total",mapper.count(user,p,accepted,r.from,r.until),"page",page,"size",size,"timezone","Asia/Shanghai"));
    }

    @GetMapping("/user/{userId}")
    public ApiResult<Map<String,Object>> publicList(@PathVariable Long userId,
        @RequestParam(required=false) String platform,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="30") int size) {
        if(page<1 || size<1 || size>100) throw ApiException.validation("页码必须为正，单页条数为 1～100");
        Long viewerId = CurrentUser.requireId();
        authService.requireReadableProfile(userId, viewerId, authService.canReadPrivateProfiles(viewerId));
        Range r=range(from,to); String p=platform(platform);
        return ApiResult.ok(Map.of("rows",mapper.publicRows(userId,p,true,r.from,r.until,size,((long)page-1)*size),
            "total",mapper.publicCount(userId,p,true,r.from,r.until),"page",page,"size",size,"timezone","Asia/Shanghai"));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required=false) String platform,
        @RequestParam(required=false) Boolean accepted,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue="false") boolean includeTags) {
        Range r=range(from,to);
        var rows=mapper.rows(CurrentUser.requireId(),platform(platform),accepted,r.from,r.until,10001,0);
        if(rows.size()>10000) throw ApiException.validation("单次最多导出 10000 条，请缩小日期或平台范围");
        var keys=new ArrayList<>(List.of("platform","submissionId","problemKey","title","url","verdict","accepted","submittedAt","timePrecision","difficulty","difficultySource","recordKind"));
        if(includeTags) keys.add("tags");
        StringBuilder csv=new StringBuilder("\uFEFF");
        csv.append(String.join(",",keys)).append("\r\n");
        for(var row:rows) {
            var cells=new ArrayList<String>();
            for(String key:keys) cells.add(csvCell(row.get(key)));
            csv.append(String.join(",",cells)).append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=algohub-history.csv")
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    static String csvCell(Object value) {
        String s=value==null ? "" : value.toString();
        if(value instanceof java.sql.Timestamp ts) s=ts.toInstant().toString();
        if(value instanceof java.time.OffsetDateTime dt) s=dt.toInstant().toString();
        String stripped=s.stripLeading();
        if(!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0 || s.startsWith("\t") || s.startsWith("\r") || s.startsWith("\n")) s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
}
