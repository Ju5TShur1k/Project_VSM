package com.vsm.okno.validation;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.PlannerResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Versioned, length-prefixed canonical representation of the actual reviewable plan events. */
public final class PlanFingerprint {
    private PlanFingerprint() {}

    public static String result(PlannerResult result) {
        return events(result.scenarioId(), result.snapshotHash(), result.blocks().stream()
                .map(b -> new Dto.PlanEvent(b.blockId(), b.trainId(), "SERVICE_BLOCK",
                        b.startAt().toString(), b.endAt().toString(), List.of(b.resourceId()))).toList());
    }

    public static String events(UUID scenarioId, String snapshotHash, List<Dto.PlanEvent> events) {
        StringBuilder canonical = new StringBuilder("d2-plan-events-v1;");
        field(canonical, scenarioId.toString());
        field(canonical, snapshotHash);
        field(canonical, Integer.toString(events.size()));
        events.stream().sorted(Comparator.comparing(e -> e.id().toString())).forEach(e -> {
            field(canonical, e.id().toString());
            field(canonical, e.trainId().toString());
            field(canonical, e.kind());
            field(canonical, OffsetDateTime.parse(e.startAt()).toInstant().toString());
            field(canonical, OffsetDateTime.parse(e.endAt()).toInstant().toString());
            field(canonical, Integer.toString(e.resourceIds().size()));
            e.resourceIds().stream().sorted().forEach(r -> field(canonical, r));
        });
        return sha256(canonical.toString());
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void field(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }
}
