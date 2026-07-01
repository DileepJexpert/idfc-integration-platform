package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.Classified;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The classified retry matrix (spec v2 §C.1) — not blind retry. */
class RetryExecutorTest {

    private static final class ClassifiedFailure extends RuntimeException implements Classified {
        private final ErrorClass ec;
        ClassifiedFailure(ErrorClass ec) { super(ec.name()); this.ec = ec; }
        @Override public ErrorClass errorClass() { return ec; }
    }

    private final AtomicInteger sleeps = new AtomicInteger();
    private final RetryExecutor executor = new RetryExecutor(millis -> sleeps.incrementAndGet());

    private java.util.function.Supplier<String> failsWith(ErrorClass ec, AtomicInteger calls) {
        return () -> { calls.incrementAndGet(); throw new ClassifiedFailure(ec); };
    }

    @Test
    void transientIsRetriedToMaxAttemptsThenThrows() {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.idempotentReads(3, Backoff.fixed(0));
        assertThatThrownBy(() -> executor.execute(policy, failsWith(ErrorClass.TRANSIENT, calls)))
                .isInstanceOf(ClassifiedFailure.class);
        assertThat(calls.get()).isEqualTo(3);          // 3 attempts
        assertThat(sleeps.get()).isEqualTo(2);         // 2 backoffs between them
    }

    @Test
    void permanentIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.idempotentReads(3, Backoff.fixed(0));
        assertThatThrownBy(() -> executor.execute(policy, failsWith(ErrorClass.PERMANENT, calls)))
                .isInstanceOf(ClassifiedFailure.class);
        assertThat(calls.get()).isEqualTo(1);          // no retry
    }

    @Test
    void ambiguousIsRetriedOnlyWhenIdempotent() {
        AtomicInteger idem = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(
                RetryPolicy.idempotentReads(3, Backoff.fixed(0)), failsWith(ErrorClass.AMBIGUOUS, idem)));
        assertThat(idem.get()).as("idempotent -> AMBIGUOUS retried as TRANSIENT").isEqualTo(3);

        AtomicInteger write = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(
                RetryPolicy.nonIdempotentWrites(3, Backoff.fixed(0)), failsWith(ErrorClass.AMBIGUOUS, write)));
        assertThat(write.get()).as("non-idempotent -> AMBIGUOUS NOT retried").isEqualTo(1);
    }

    @Test
    void unclassifiedExceptionIsTreatedAsPermanentNoRetry() {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.idempotentReads(3, Backoff.fixed(0));
        assertThatThrownBy(() -> executor.execute(policy, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void succeedsOnRetryWithoutThrowing() {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.idempotentReads(3, Backoff.fixed(0));
        String result = executor.execute(policy, () -> {
            if (calls.incrementAndGet() < 2) throw new ClassifiedFailure(ErrorClass.TRANSIENT);
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void backoffIsExponentialAndCapped_jitterStaysWithinBound() {
        Backoff noJitter = new Backoff(100, 5000, false, () -> 1.0);
        assertThat(noJitter.delayMillis(1)).isEqualTo(100);
        assertThat(noJitter.delayMillis(2)).isEqualTo(200);
        assertThat(noJitter.delayMillis(3)).isEqualTo(400);
        assertThat(noJitter.delayMillis(10)).isEqualTo(5000);   // capped

        Backoff jitter = new Backoff(100, 5000, true, () -> 0.5);
        assertThat(jitter.delayMillis(3)).isEqualTo(200);       // 0.5 * 400 (capped 400)
        Backoff jitterZero = new Backoff(100, 5000, true, () -> 0.0);
        assertThat(jitterZero.delayMillis(5)).isBetween(0L, 1600L);
    }
}
