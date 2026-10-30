package com.szpu.algohub.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 条数参数夹在 [1, max]；模糊搜索关键字里的通配符按普通字符处理。 */
class InputBoundsTest {

    @Test
    void limitIsClampedIntoRange() {
        assertThat(Limits.clamp(-5, 200)).isEqualTo(1);
        assertThat(Limits.clamp(0, 200)).isEqualTo(1);
        assertThat(Limits.clamp(30, 200)).isEqualTo(30);
        assertThat(Limits.clamp(Integer.MAX_VALUE, 200)).isEqualTo(200);
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(SqlLike.escape("a_b%c\\d")).isEqualTo("a\\_b\\%c\\\\d");
        assertThat(SqlLike.escape("普通")).isEqualTo("普通");
        assertThat(SqlLike.escape(null)).isNull();
    }
}
