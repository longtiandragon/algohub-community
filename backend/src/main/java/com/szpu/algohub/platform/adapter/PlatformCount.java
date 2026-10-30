package com.szpu.algohub.platform.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;

/** Missing or malformed counts must not overwrite a successful snapshot with zero. */
final class PlatformCount {
    private PlatformCount() {}
    static int read(JsonNode value, String field) {
        if (value != null && (value.isIntegralNumber() || value.isTextual())) {
            String raw = value.asText();
            if (raw.matches("[0-9]+")) {
                try { return Integer.parseInt(raw); }
                catch (NumberFormatException ignored) { }
            }
        }
        throw new ApiException(ErrorKind.PARSE_ERROR, field + "不是有效的非负整数，保留原有统计");
    }
}
