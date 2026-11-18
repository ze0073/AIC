package com.shijie.accessibility.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class UlidsTest {
    @Test
    void createsUniqueCrockfordIds() {
        var values = new HashSet<String>();
        for (int i = 0; i < 2_000; i++) values.add(Ulids.next());
        assertThat(values).hasSize(2_000).allMatch(id -> id.matches("[0-9A-HJKMNP-TV-Z]{26}"));
    }
}

