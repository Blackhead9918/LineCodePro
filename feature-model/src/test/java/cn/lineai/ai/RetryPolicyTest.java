package cn.lineai.ai;

import org.junit.Assert;
import org.junit.Test;

public final class RetryPolicyTest {

    @Test
    public void permanentClientErrorsAreNotRetryable() {
        Assert.assertFalse(RetryPolicy.isRetryableStatusCode(400));
        Assert.assertFalse(RetryPolicy.isRetryableStatusCode(401));
        Assert.assertFalse(RetryPolicy.isRetryableStatusCode(403));
        Assert.assertFalse(RetryPolicy.isRetryableStatusCode(404));
        Assert.assertFalse(RetryPolicy.isRetryableStatusCode(422));
    }

    @Test
    public void transientAndServerErrorsAreRetryable() {
        Assert.assertTrue(RetryPolicy.isRetryableStatusCode(408));
        Assert.assertTrue(RetryPolicy.isRetryableStatusCode(429));
        Assert.assertTrue(RetryPolicy.isRetryableStatusCode(500));
        Assert.assertTrue(RetryPolicy.isRetryableStatusCode(502));
        Assert.assertTrue(RetryPolicy.isRetryableStatusCode(503));
    }

    @Test
    public void unknownStatusIsRetryable() {
        Assert.assertTrue(RetryPolicy.isRetryableStatusCode(-1));
        Assert.assertTrue(RetryPolicy.isRetryableStatusCode(0));
    }

    @Test
    public void backoffDoublesExponentially() {
        Assert.assertEquals(2000L, RetryPolicy.backoffDelayMs(1));
        Assert.assertEquals(4000L, RetryPolicy.backoffDelayMs(2));
        Assert.assertEquals(8000L, RetryPolicy.backoffDelayMs(3));
        Assert.assertEquals(16000L, RetryPolicy.backoffDelayMs(4));
    }

    @Test
    public void backoffCapsAtMaxDelay() {
        Assert.assertEquals(16000L, RetryPolicy.backoffDelayMs(5));
        Assert.assertEquals(16000L, RetryPolicy.backoffDelayMs(10));
        Assert.assertEquals(16000L, RetryPolicy.backoffDelayMs(100));
    }

    @Test
    public void backoffNeverNegative() {
        Assert.assertEquals(2000L, RetryPolicy.backoffDelayMs(0));
    }

    @Test
    public void jitterStaysWithinBounds() {
        for (int attempt = 1; attempt <= 6; attempt++) {
            long delay = RetryPolicy.delayWithJitterMs(attempt);
            long base = RetryPolicy.backoffDelayMs(attempt);
            Assert.assertTrue("delay too small: " + delay, delay >= base);
            Assert.assertTrue("delay too large: " + delay, delay < base + 500L);
        }
    }
}
