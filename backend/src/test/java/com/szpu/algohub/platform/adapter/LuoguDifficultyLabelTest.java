package com.szpu.algohub.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 洛谷难度标签用官网的评级名。
 *
 * 「洛谷难度 2」对同学没有意义，他在 luogu.com.cn 上看到的是「普及−」。
 * 原始编号仍保存在 difficulty_raw，这里只改展示用的名字，不改任何统计口径，
 * 也不把编号换算成别的平台的难度。
 */
class LuoguDifficultyLabelTest {

    @Test
    void everyOfficialTierGetsItsRealName() {
        assertThat(LuoguPracticeParser.difficultyLabel("0")).isEqualTo("暂无评定");
        assertThat(LuoguPracticeParser.difficultyLabel("1")).isEqualTo("入门");
        // 洛谷现行九档（官网配置），与同学主页「难易度统计」逐档一致；减号是全角「−」
        assertThat(LuoguPracticeParser.difficultyLabel("2")).isEqualTo("普及−");
        assertThat(LuoguPracticeParser.difficultyLabel("3")).isEqualTo("普及");
        assertThat(LuoguPracticeParser.difficultyLabel("4")).isEqualTo("普及+/提高−");
        assertThat(LuoguPracticeParser.difficultyLabel("5")).isEqualTo("提高");
        assertThat(LuoguPracticeParser.difficultyLabel("6")).isEqualTo("提高+/省选−");
        assertThat(LuoguPracticeParser.difficultyLabel("7")).isEqualTo("省选/NOI−");
        assertThat(LuoguPracticeParser.difficultyLabel("8")).isEqualTo("NOI/NOI+/CTS");
    }

    @Test
    void missingDifficultyStaysNullInsteadOfGuessingATier() {
        assertThat(LuoguPracticeParser.difficultyLabel(null)).isNull();
    }

    @Test
    void unexpectedValuesAreShownAsIsRatherThanMappedToTheNearestTier() {
        // 源站要是再加新档次，宁可显示成原始编号，也不硬塞进已知的九档里
        assertThat(LuoguPracticeParser.difficultyLabel("9")).isEqualTo("洛谷难度 9");
        assertThat(LuoguPracticeParser.difficultyLabel("-1")).isEqualTo("洛谷难度 -1");
    }
}
