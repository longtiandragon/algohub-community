package com.szpu.algohub.platform;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.platform.PlatformDtos.OwnershipProof;
import com.szpu.algohub.platform.PlatformDtos.PlatformCapability;

@Component
public class PlatformRegistry {

    private final Map<Platform, PlatformAdapter> adapters = new EnumMap<>(Platform.class);

    public PlatformRegistry(List<PlatformAdapter> adapterList) {
        adapterList.forEach(a -> adapters.put(a.platform(), a));
    }

    public Optional<PlatformAdapter> find(Platform platform) {
        if (platform == Platform.LANQIAO) return Optional.empty();
        return Optional.ofNullable(adapters.get(platform));
    }

    public PlatformAdapter require(Platform platform) {
        return find(platform).orElseThrow(() -> new ApiException(ErrorKind.UNSUPPORTED,
                platform.getDisplayName() + " 暂未实现适配器，可先登记账号链接但不会同步"));
    }

    public List<PlatformAdapter> all() {
        return adapters.entrySet().stream().filter(e -> e.getKey() != Platform.LANQIAO)
                .map(Map.Entry::getValue).toList();
    }

    /** 平台的公开资料归属证明方式；没有适配器或平台无法证明时为 null。 */
    public OwnershipProof proofOf(Platform platform) {
        return find(platform).map(PlatformAdapter::ownershipProof).orElse(null);
    }

    /** 所有平台的能力清单：未实现适配器的平台也要如实列出，标为暂不支持同步。 */
    public List<PlatformCapability> capabilities() {
        return java.util.Arrays.stream(Platform.values())
                .filter(p -> p != Platform.LANQIAO)
                .map(p -> find(p)
                        .map(PlatformAdapter::capability)
                        .orElse(new PlatformCapability(p, false, false, false, false,
                                "NONE", "NONE", false, "暂不支持同步，仅展示导航与用户填写的账号链接")))
                .toList();
    }
}
