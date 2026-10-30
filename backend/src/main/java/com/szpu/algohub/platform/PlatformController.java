package com.szpu.algohub.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.szpu.algohub.common.ApiResult;
import com.szpu.algohub.platform.PlatformDtos.OwnershipProof;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;

@RestController
@RequestMapping("/api/platforms")
public class PlatformController {

    private final PlatformRegistry registry;

    public PlatformController(PlatformRegistry registry) {
        this.registry = registry;
    }

    /** 平台能力清单：每项能力分别说明，未实现同步的平台也如实列出。 */
    @GetMapping("/capabilities")
    public ApiResult<List<Map<String, Object>>> capabilities() {
        List<Map<String, Object>> result = registry.capabilities().stream()
                .map(this::toView)
                .toList();
        return ApiResult.ok(result);
    }

    private Map<String, Object> toView(PlatformCapability capability) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("platform", capability.platform().name());
        view.put("displayName", capability.platform().getDisplayName());
        view.put("homeUrl", capability.platform().getHomeUrl());
        view.put("publicIdentity", capability.publicIdentity());
        view.put("submissionHistory", capability.submissionHistory());
        view.put("totalsSnapshot", capability.totalsSnapshot());
        view.put("requiresCredential", capability.requiresCredential());
        view.put("difficultySource", capability.difficultySource());
        view.put("tagSource", capability.tagSource());
        view.put("contestList", capability.contestList());
        view.put("notes", capability.notes());
        view.put("syncSupported", capability.submissionHistory() || capability.totalsSnapshot());
        // 能否用公开资料证明归属：不能的平台只能添加为个人关注，不进入校内榜单
        OwnershipProof proof = registry.proofOf(capability.platform());
        view.put("ownershipProof", proof == null ? null : Map.of(
                "location", proof.location(),
                "note", proof.note() == null ? "" : proof.note(),
                "validMinutes", proof.validMinutes()));
        return view;
    }
}
