package com.vsm.okno.requests;

import com.vsm.okno.service.PlanningService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Schedule CSV -> one new source version. Header (Russian or English names, "," or ";"):
 * рейс/trip, состав/train, отправление/departure, прибытие/arrival, and for new trips
 * откуда/origin, куда/destination, км/km. A known trip label with new times becomes a
 * TRIP_CHANGE, an unknown label a TRIP_ADD; rows equal to the current data are skipped.
 * Times are ISO-8601 with offset or "yyyy-MM-dd HH:mm" Moscow time.
 */
@Service
@Profile("database")
public class ScheduleImportService {
    public record Result(int changed, int added, int unchanged, RequestDto.Receipt receipt) {}

    private static final Map<String, String> HEADERS = Map.ofEntries(
            Map.entry("рейс", "trip"), Map.entry("trip", "trip"),
            Map.entry("состав", "train"), Map.entry("train", "train"),
            Map.entry("отправление", "departure"), Map.entry("departure", "departure"),
            Map.entry("прибытие", "arrival"), Map.entry("arrival", "arrival"),
            Map.entry("откуда", "origin"), Map.entry("origin", "origin"),
            Map.entry("куда", "destination"), Map.entry("destination", "destination"),
            Map.entry("км", "km"), Map.entry("km", "km"));
    private static final Map<String, String> CITIES = Map.of(
            "москва", "MOSCOW", "moscow", "MOSCOW",
            "санкт-петербург", "SPB_DEPOT", "спб", "SPB_DEPOT", "spb_depot", "SPB_DEPOT");
    private static final ZoneOffset MOSCOW = ZoneOffset.ofHours(3);

    private final SourceVersionService versions;
    private final ObjectMapper json = new ObjectMapper();

    public ScheduleImportService(SourceVersionService versions) { this.versions = versions; }

    public Result importCsv(UUID scenarioId, String csv, String actor) {
        if (csv == null || csv.isBlank()) throw invalid("csv", "is empty");
        List<String> lines = csv.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.size() < 2) throw invalid("csv", "needs a header and at least one row");
        String sep = lines.getFirst().contains(";") ? ";" : ",";
        List<String> header = Arrays.stream(lines.getFirst().replace("﻿", "").split(sep))
                .map(h -> HEADERS.get(h.strip().toLowerCase(Locale.ROOT))).toList();
        for (String required : List.of("trip", "train", "departure", "arrival"))
            if (!header.contains(required)) throw invalid("csv", "header needs column " + required + " (рейс, состав, отправление, прибытие)");

        var head = versions.head(scenarioId);
        JsonNode source = versions.source(head.scenarioId());
        Map<String, String> trainByName = new HashMap<>();
        for (JsonNode t : source.path("trains")) trainByName.put(t.path("external_id").asText(), t.path("id").asText());
        // Paired departures share a label, so a trip is the label on a given train.
        Map<String, JsonNode> tripByKey = new HashMap<>();
        for (JsonNode t : source.path("fixedTrips")) tripByKey.put(t.path("label").asText() + "|" + t.path("train_id").asText(), t);

        List<JsonNode> changes = new ArrayList<>();
        int changed = 0, added = 0, unchanged = 0;
        for (int n = 1; n < lines.size(); n++) {
            String[] cells = lines.get(n).split(sep, -1);
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.size() && i < cells.length; i++)
                if (header.get(i) != null) row.put(header.get(i), cells[i].strip());
            String line = "строка " + (n + 1) + ": ";
            String trainId = trainByName.get(row.getOrDefault("train", ""));
            if (trainId == null) throw invalid("csv", line + "неизвестный состав " + row.get("train"));
            OffsetDateTime dep = time(row.get("departure"), line), arr = time(row.get("arrival"), line);
            if (!arr.isAfter(dep)) throw invalid("csv", line + "прибытие должно быть позже отправления");
            JsonNode existing = tripByKey.get(row.getOrDefault("trip", "") + "|" + trainId);
            ObjectNode change = json.createObjectNode().put("trainId", trainId)
                    .put("departureAt", dep.toString()).put("arrivalAt", arr.toString());
            if (existing != null) {
                if (OffsetDateTime.parse(existing.path("departure_at").asText()).isEqual(dep)
                        && OffsetDateTime.parse(existing.path("arrival_at").asText()).isEqual(arr)) { unchanged++; continue; }
                changes.add(change.put("kind", "TRIP_CHANGE").put("tripId", existing.path("id").asText()));
                changed++;
            } else {
                String origin = city(row.get("origin"), line), destination = city(row.get("destination"), line);
                long km;
                try { km = Long.parseLong(row.getOrDefault("km", "")); } catch (NumberFormatException e) { throw invalid("csv", line + "для нового рейса нужен км"); }
                changes.add(change.put("kind", "TRIP_ADD").put("tripId", UUID.randomUUID().toString())
                        .put("label", row.get("trip")).put("origin", origin).put("destination", destination).put("distanceKm", km));
                added++;
            }
        }
        var receipt = versions.importSchedule(head.scenarioId(), head.version(), changes, "Загрузка CSV / " + actor, actor);
        return new Result(changed, added, unchanged, receipt);
    }

    private static OffsetDateTime time(String value, String line) {
        if (value == null || value.isBlank()) throw invalid("csv", line + "нет времени");
        try { return OffsetDateTime.parse(value); } catch (DateTimeParseException ignored) { /* local Moscow */ }
        try { return LocalDateTime.parse(value.replace(' ', 'T')).atOffset(MOSCOW); }
        catch (DateTimeParseException e) { throw invalid("csv", line + "время " + value + " не в формате 2031-07-01 06:05"); }
    }

    private static String city(String value, String line) {
        String c = value == null ? null : CITIES.get(value.strip().toLowerCase(Locale.ROOT));
        if (c == null) throw invalid("csv", line + "город " + value + " не Москва и не Санкт-Петербург");
        return c;
    }

    private static PlanningService.InvalidRequestException invalid(String field, String reason) {
        return new PlanningService.InvalidRequestException(field, reason);
    }
}
