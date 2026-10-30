package com.szpu.algohub.statistics;

import java.util.Map;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.common.ApiResult;

@RestController
@RequestMapping("/api/ai-archive")
public class AiArchiveController {
    private final AiArchiveService service;
    public AiArchiveController(AiArchiveService service) { this.service=service; }

    @PostMapping
    public ResponseEntity<ApiResult<Map<String,Object>>> generate(@Valid @RequestBody AiArchiveService.Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.ok(service.generate(CurrentUser.requireId(),request)));
    }

    @GetMapping("/skill")
    public ResponseEntity<byte[]> skill() throws IOException {
        CurrentUser.requireId();
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("ai-learning-review/SKILL.md"));
            zip.write(service.skill().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=ai-learning-review.zip")
                .contentType(MediaType.parseMediaType("application/zip")).body(bytes.toByteArray());
    }
}
