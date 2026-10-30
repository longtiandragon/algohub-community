package com.szpu.algohub.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContestCategoryRulesTest {

    @Test
    void codeforcesDivisionsComeFromNameNotContestType() {
        assertThat(ContestCategoryRules.classifyCodeforces("Codeforces Round 900 (Div. 2)")).isEqualTo("Div.2");
        assertThat(ContestCategoryRules.classifyCodeforces("Educational Codeforces Round 170 (Rated for Div. 2)"))
                .isEqualTo("Educational+Div.2");
        // 联合场次允许多个标签
        assertThat(ContestCategoryRules.classifyCodeforces("Codeforces Round 899 (Div. 1 + Div. 2)"))
                .isEqualTo("Div.1+Div.2");
        assertThat(ContestCategoryRules.classifyCodeforces("Codeforces Global Round 27")).isEqualTo("Global");
        assertThat(ContestCategoryRules.classifyCodeforces("Some Unusual Cup")).isEqualTo("其他");
    }

    @Test
    void atcoderCategoriesFollowContestId() {
        assertThat(ContestCategoryRules.classifyAtCoder("abc300", "AtCoder Beginner Contest 300")).isEqualTo("ABC");
        assertThat(ContestCategoryRules.classifyAtCoder("arc180", "AtCoder Regular Contest 180")).isEqualTo("ARC");
        assertThat(ContestCategoryRules.classifyAtCoder("agc070", "AtCoder Grand Contest 070")).isEqualTo("AGC");
        assertThat(ContestCategoryRules.classifyAtCoder("ahc040", "AtCoder Heuristic Contest 040")).isEqualTo("AHC");
        assertThat(ContestCategoryRules.classifyAtCoder("tenka1", "Tenka1 Programmer Contest")).isEqualTo("其他");
    }

    @Test
    void leetcodeWeeklyAndBiweekly() {
        assertThat(ContestCategoryRules.classifyLeetCode("第 519 场周赛")).isEqualTo("周赛");
        assertThat(ContestCategoryRules.classifyLeetCode("第 160 场双周赛")).isEqualTo("双周赛");
    }
}
