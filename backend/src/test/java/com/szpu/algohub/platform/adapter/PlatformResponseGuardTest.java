package com.szpu.algohub.platform.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.*;
import com.szpu.algohub.platform.PlatformDtos.SyncContext;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlatformResponseGuardTest {
    final ObjectMapper json=new ObjectMapper();
    final SyncContext context=new SyncContext(1L,"test","1",null,"test-cookie",null);
    @Test void acceptsRealZeroAndIntegerStringsButRejectsInvalidCounts() throws Exception {
        assertThat(PlatformCount.read(json.readTree("0"),"count")).isZero();
        assertThat(PlatformCount.read(json.readTree("\"41\""),"count")).isEqualTo(41);
        for(String raw: new String[]{"null","-1","1.5","true","\"unknown\"","2147483648"}) {
            var value=json.readTree(raw);
            assertThatThrownBy(()->PlatformCount.read(value,"count")).isInstanceOf(ApiException.class);
        }
    }
    @Test void leetcodeDoesNotTurnMissingCountsIntoZeroOrDoubleCountDifficulty() {
        var http=mock(PlatformHttpClient.class);
        var adapter=new LeetCodeCnAdapter(http);
        for(String rows:new String[]{"[]","[{\"difficulty\":\"EASY\"}]","[{\"difficulty\":\"EASY\",\"count\":1},{\"difficulty\":\"EASY\",\"count\":2}]"}) {
            when(http.postJson(eq(Platform.LEETCODE_CN),anyString(),anyString(),anyMap(),any()))
                .thenReturn("{\"data\":{\"userProfileUserQuestionProgressV2\":{\"numAcceptedQuestions\":"+rows+"}}}");
            assertThatThrownBy(()->adapter.fetchTotals(context)).isInstanceOf(ApiException.class);
        }
    }
    @Test void qojLoginPageIsNotAZeroSnapshot() {
        var http=mock(PlatformHttpClient.class);
        when(http.get(eq(Platform.QOJ),anyString(),anyMap(),any()))
            .thenReturn("<title>QOJ</title><form><input type=password></form>");
        assertThatThrownBy(()->new QojAdapter(http).fetchTotals(context)).hasMessageContaining("登录页面");
    }
    @Test void qojCountsUniqueProblemLinksOnly() {
        var http=mock(PlatformHttpClient.class);
        when(http.get(eq(Platform.QOJ),anyString(),anyMap(),any()))
            .thenReturn("<title>Profile</title><ul class='list-group-item-text nav nav-pills uoj-ac-problems-list'><li><a href='/problem/1'>A</a></li><li><a href='https://qoj.ac/problem/1'>A</a></li><li><a href='/problem/2'>B</a></li><a href='/user/profile/test'>user</a></ul>");
        assertThat(new QojAdapter(http).fetchTotals(context).solvedTotal()).isEqualTo(2);
    }
}
