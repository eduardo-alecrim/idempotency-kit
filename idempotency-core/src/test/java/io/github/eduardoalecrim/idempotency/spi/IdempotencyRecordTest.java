// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class IdempotencyRecordTest {

    private static final ScopeKey KEY = new ScopeKey("a".repeat(64));
    private static final ScopeParts PARTS = new ScopeParts("k", "", "POST", "/orders");
    private static final Fingerprint FINGERPRINT = new Fingerprint("b".repeat(64));
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final StoredResponse RESPONSE = new StoredResponse(201, List.of(), new byte[0], false);

    private final OwnerToken token = OwnerToken.random();

    @Test
    void claimCreatesAnInProgressRecord() {
        ClaimRequest claim = new ClaimRequest(KEY, PARTS, FINGERPRINT, NOW, NOW.plusSeconds(30), NOW.plusSeconds(60));

        IdempotencyRecord record = claim.toRecord(token);

        assertThat(record.state()).isEqualTo(RecordState.IN_PROGRESS);
        assertThat(record.ownerToken()).isEqualTo(token);
        assertThat(record.leaseUntil()).isEqualTo(NOW.plusSeconds(30));
        assertThat(record.createdAt()).isEqualTo(NOW);
        assertThat(record.isOwnedBy(token)).isTrue();
        assertThat(record.isOwnedBy(OwnerToken.random())).isFalse();
    }

    @Test
    void claimRejectsLeaseNotAfterNowAndExpiryBeforeLease() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClaimRequest(KEY, PARTS, FINGERPRINT, NOW, NOW, NOW.plusSeconds(60)));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new ClaimRequest(KEY, PARTS, FINGERPRINT, NOW, NOW.plusSeconds(30), NOW.plusSeconds(29)));
    }

    @Test
    void completeDropsTokenAndLease() {
        IdempotencyRecord completed = inProgress().complete(RESPONSE, NOW.plusSeconds(1), NOW.plusSeconds(3600));

        assertThat(completed.state()).isEqualTo(RecordState.COMPLETED);
        assertThat(completed.ownerToken()).isNull();
        assertThat(completed.leaseUntil()).isNull();
        assertThat(completed.response()).isEqualTo(RESPONSE);
        assertThat(completed.completedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(completed.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(completed.isOwnedBy(token)).isFalse();
    }

    @Test
    void inProgressExpiresWhenItsLeaseEnds() {
        IdempotencyRecord record = inProgress();

        assertThat(record.isExpired(NOW.plusSeconds(30))).isFalse();
        assertThat(record.isExpired(NOW.plusSeconds(30).plusNanos(1))).isTrue();
    }

    @Test
    void completedExpiresWhenItsTtlEnds() {
        IdempotencyRecord record = inProgress().complete(RESPONSE, NOW, NOW.plusSeconds(3600));

        assertThat(record.isExpired(NOW.plusSeconds(3600))).isFalse();
        assertThat(record.isExpired(NOW.plusSeconds(3601))).isTrue();
    }

    @Test
    void rejectsFieldsThatDoNotMatchTheState() {
        assertThatIllegalArgumentException().isThrownBy(() -> record(RecordState.IN_PROGRESS, null, NOW, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> record(RecordState.IN_PROGRESS, token, null, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> record(RecordState.IN_PROGRESS, token, NOW, RESPONSE, null));
        assertThatIllegalArgumentException().isThrownBy(() -> record(RecordState.IN_PROGRESS, token, NOW, null, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> record(RecordState.COMPLETED, null, null, null, NOW));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> record(RecordState.COMPLETED, null, null, RESPONSE, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> record(RecordState.COMPLETED, token, null, RESPONSE, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> record(RecordState.COMPLETED, null, NOW, RESPONSE, NOW));
    }

    @Test
    void summaryDropsBodyAndToken() {
        RecordSummary inProgress = RecordSummary.of(inProgress());
        RecordSummary completed = RecordSummary.of(inProgress().complete(RESPONSE, NOW, NOW.plusSeconds(60)));

        assertThat(inProgress.status()).isNull();
        assertThat(inProgress.state()).isEqualTo(RecordState.IN_PROGRESS);
        assertThat(completed.status()).isEqualTo(201);
        assertThat(completed.expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void claimResultsCarryTheirValues() {
        assertThat(new ClaimResult.Acquired(token).token()).isEqualTo(token);
        assertThat(new ClaimResult.Existing(inProgress()).record().key()).isEqualTo(KEY);
    }

    private IdempotencyRecord inProgress() {
        return IdempotencyRecord.inProgress(
                KEY, PARTS, FINGERPRINT, token, NOW.plusSeconds(30), NOW, NOW.plusSeconds(3600));
    }

    private static IdempotencyRecord record(
            RecordState state,
            @Nullable OwnerToken ownerToken,
            @Nullable Instant leaseUntil,
            @Nullable StoredResponse response,
            @Nullable Instant completedAt) {
        return new IdempotencyRecord(
                KEY,
                PARTS,
                FINGERPRINT,
                state,
                ownerToken,
                leaseUntil,
                response,
                NOW,
                completedAt,
                NOW.plusSeconds(60));
    }
}
