package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RequestContextTest {

  @Test
  void recordFieldsAreAccessible() {
    var ctx = new RequestContext("client-42", "trace-abc");

    assertEquals("client-42", ctx.clientId());
    assertEquals("trace-abc", ctx.traceId());
  }

  @Test
  void equalityIsStructural() {
    var a = new RequestContext("c1", "t1");
    var b = new RequestContext("c1", "t1");
    var c = new RequestContext("c2", "t1");

    assertEquals(a, b);
    assertNotEquals(a, c);
  }

  @Test
  void scopedValueIsBound() {
    var ctx = new RequestContext("tenant-1", "trace-xyz");

    ScopedValue.where(RequestContext.CURRENT, ctx).run(() -> {
      assertTrue(RequestContext.CURRENT.isBound());
      assertEquals(ctx, RequestContext.CURRENT.get());
    });

    assertFalse(RequestContext.CURRENT.isBound());
  }

  @Test
  void scopedValuePropagatesInVirtualThread() throws Exception {
    var ctx = new RequestContext("tenant-2", "trace-999");

    ScopedValue.where(RequestContext.CURRENT, ctx).run(() -> {
      try {
        var thread = Thread.ofVirtual().start(() -> {
          assertTrue(RequestContext.CURRENT.isBound());
          assertEquals(ctx, RequestContext.CURRENT.get());
        });
        thread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    });
  }
}
