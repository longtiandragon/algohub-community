package com.szpu.algohub.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact payload receipt: changing any field of a retried page is rejected. */
final class BrowserImportPageHash {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BrowserImportPageHash() {}

    static String of(Object page) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(page)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot hash browser import page", e);
        }
    }
}
