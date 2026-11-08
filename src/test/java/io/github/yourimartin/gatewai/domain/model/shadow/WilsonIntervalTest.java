package io.github.yourimartin.gatewai.domain.model.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Against published values: the 95 % Wilson interval of 0/10 is [0, 0.2775] and of
 * 5/10 is [0.2366, 0.7634] (Wilson 1927; tabulated in Newcombe, "Two-sided confidence
 * intervals for the single proportion", Stat. Med. 17, 1998).
 */
class WilsonIntervalTest {

  private static final double TOLERANCE = 5e-5;

  @Test
  void matchesPublishedValues() {
    WilsonInterval none = WilsonInterval.of(0, 10);
    assertThat(none.low()).isEqualTo(0.0);
    assertThat(none.high()).isCloseTo(0.2775, within(TOLERANCE));

    WilsonInterval half = WilsonInterval.of(5, 10);
    assertThat(half.low()).isCloseTo(0.2366, within(TOLERANCE));
    assertThat(half.high()).isCloseTo(0.7634, within(TOLERANCE));

    WilsonInterval all = WilsonInterval.of(10, 10);
    assertThat(all.low()).isCloseTo(0.7225, within(TOLERANCE));
    assertThat(all.high()).isCloseTo(1.0, within(1e-12));
  }

  @Test
  void narrowsWithTheSampleAndStaysInsideZeroOne() {
    WilsonInterval small = WilsonInterval.of(3, 20);
    WilsonInterval large = WilsonInterval.of(30, 200);

    assertThat(large.high() - large.low()).isLessThan(small.high() - small.low());
    assertThat(WilsonInterval.of(1, 1).high()).isLessThanOrEqualTo(1.0);
    assertThat(WilsonInterval.of(0, 1).low()).isGreaterThanOrEqualTo(0.0);
  }

  @Test
  void anIntervalOfNothingIsRefusedNotZero() {
    assertThatThrownBy(() -> WilsonInterval.of(0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WilsonInterval.of(3, 2)).isInstanceOf(IllegalArgumentException.class);
  }
}
