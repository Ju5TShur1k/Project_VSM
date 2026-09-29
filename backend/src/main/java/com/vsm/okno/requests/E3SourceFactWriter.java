package com.vsm.okno.requests;

import com.vsm.okno.service.PlanningService;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Writes into the unpublished cloned source inside SourceVersionService's transaction. */
final class E3SourceFactWriter {
    private final JdbcTemplate jdbc;
    E3SourceFactWriter(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    void apply(UUID scenario, JsonNode change, String source, String actor) {
        if ("TRAIN_RELEASE".equals(text(change,"kind"))) release(scenario,change,source,actor);
        else rules(scenario,change,source);
    }

    private void release(UUID scenario,JsonNode c,String source,String actor) {
        fields(c,Set.of("kind","trainId","frozenWorkId","availableFrom","location","basis","acceptedAt","acceptanceDocument"));
        UUID train=uuid(c,"trainId"), work=optionalUuid(c,"frozenWorkId");
        check(jdbc.queryForObject("select count(*) from vsm.train where scenario_id=? and id=?",Integer.class,scenario,train)==1,"trainId","does not exist");
        OffsetDateTime available=time(c,"availableFrom"), accepted=optionalTime(c,"acceptedAt");
        var horizon=jdbc.queryForObject("select horizon_end from vsm.scenario where id=?",OffsetDateTime.class,scenario);
        check(available.isBefore(horizon),"availableFrom","must be before the end of the horizon");
        String basis=text(c,"basis"), location=text(c,"location"), document=optionalText(c,"acceptanceDocument");
        check(Set.of("ACCEPTED","FORECAST","DEMO_ASSUMPTION").contains(basis),"basis","is unsupported");
        check(jdbc.queryForObject("""
                select exists(select 1 from vsm.train where scenario_id=? and location=?
                  union all select 1 from vsm.resource where scenario_id=? and location=?)
                """,Boolean.class,scenario,location,scenario,location),"location","must name a known city/depot");
        if ("ACCEPTED".equals(basis)) {
            check(accepted!=null && document!=null,"acceptedAt","and acceptanceDocument are required for an actual release");
            check(!accepted.isAfter(available) && !accepted.toInstant().isAfter(Instant.now()),"acceptedAt","cannot be after availableFrom or in the future; use a demo assumption for model time");
        } else check(accepted==null && document==null,"acceptedAt","and acceptanceDocument are forbidden for a forecast or demo assumption");
        if ("DEMO_ASSUMPTION".equals(basis)) modelled(scenario);
        if (work!=null) {
            var found=jdbc.query("""
                    select w.train_id,w.ends_at,r.location from vsm.frozen_work w join vsm.resource r
                    on r.scenario_id=w.scenario_id and r.id=w.resource_id where w.scenario_id=? and w.id=?
                    """,(rs,n)->new Work(rs.getObject("train_id",UUID.class),rs.getObject("ends_at",OffsetDateTime.class),rs.getString("location")),scenario,work);
            check(found.size()==1,"frozenWorkId","does not exist");
            var row=found.getFirst();
            check(train.equals(row.train()) && location.equals(row.location()),"frozenWorkId","must belong to this train and release depot");
            check(!available.isBefore(row.end()) && (accepted==null || !accepted.isBefore(row.end())),"availableFrom","release and acceptance cannot precede the named work's end");
        }
        String confirmation=switch(basis) { case "ACCEPTED" -> "CONFIRMED"; case "FORECAST" -> "UNCONFIRMED"; default -> "SYNTHETIC"; };
        jdbc.update("""
                insert into vsm.train_release(scenario_id,id,train_id,frozen_work_id,available_from,location,basis,
                  accepted_at,acceptance_document,confirmation_status,source,recorded_by) values (?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict (scenario_id,train_id) do update set id=excluded.id,frozen_work_id=excluded.frozen_work_id,
                  available_from=excluded.available_from,location=excluded.location,basis=excluded.basis,
                  accepted_at=excluded.accepted_at,acceptance_document=excluded.acceptance_document,
                  confirmation_status=excluded.confirmation_status,source=excluded.source,recorded_by=excluded.recorded_by
                """,scenario,UUID.randomUUID(),train,work,available,location,basis,accepted,document,confirmation,source,actor);
    }

    private void rules(UUID scenario,JsonNode c,String source) {
        fields(c,Set.of("kind","version","rules"));
        String version=text(c,"version");
        check(c.path("rules").isArray() && !c.path("rules").isEmpty() && c.path("rules").size()<=100,"rules","must contain 1..100 rules; the whole catalog is replaced");
        jdbc.update("delete from vsm.urgent_work_rule_resource where scenario_id=?",scenario);
        jdbc.update("delete from vsm.urgent_work_rule where scenario_id=?",scenario);
        Set<String> kinds=new HashSet<>();
        for (JsonNode rule:c.path("rules")) {
            fields(rule,Set.of("workKind","durationMinutes","resourceIds","confirmationStatus"));
            String kind=text(rule,"workKind"),confirmation=text(rule,"confirmationStatus");
            check(kinds.add(kind),"workKind","is duplicated");
            check(Set.of("CONFIRMED","UNCONFIRMED","SYNTHETIC").contains(confirmation),"confirmationStatus","is unsupported");
            if ("SYNTHETIC".equals(confirmation)) modelled(scenario);
            JsonNode duration=rule.path("durationMinutes");
            check(duration.isIntegralNumber() && duration.canConvertToInt() && duration.asInt()>0,"durationMinutes","must be a positive integer");
            check(rule.path("resourceIds").isArray() && !rule.path("resourceIds").isEmpty(),"resourceIds","must contain allowed alternatives, not a reserved slot");
            UUID id=UUID.randomUUID();
            jdbc.update("insert into vsm.urgent_work_rule values (?,?,?,?,?,?,?)",scenario,id,kind,version,duration.asInt(),confirmation,source);
            Set<String> resources=new HashSet<>();
            for (var resource:rule.path("resourceIds")) {
                check(resource.isTextual() && !resource.asText().isBlank() && resources.add(resource.asText()),"resourceIds","must contain distinct resource IDs");
                check(jdbc.queryForObject("select count(*) from vsm.resource where scenario_id=? and id=?",Integer.class,scenario,resource.asText())==1,"resourceIds","unknown resource "+resource.asText());
                jdbc.update("insert into vsm.urgent_work_rule_resource values (?,?,?)",scenario,id,resource.asText());
            }
        }
    }
    private void modelled(UUID scenario) {
        String provenance=jdbc.queryForObject("select provenance from vsm.scenario where id=?",String.class,scenario);
        check(provenance!=null && (provenance.toUpperCase(java.util.Locale.ROOT).startsWith("MODELLED ")
                || provenance.toUpperCase(java.util.Locale.ROOT).startsWith("SYNTHETIC")
                || "Демонстрационные данные".equals(provenance)),"basis","demonstration assumptions are permitted only on explicitly modelled sources");
    }
    private record Work(UUID train,OffsetDateTime end,String location) {}
    private static void fields(JsonNode c,Set<String> allowed) { check(c.isObject(),"change","must be an object"); for (String key:c.propertyNames()) check(allowed.contains(key),key,"is unsupported"); }
    private static String text(JsonNode c,String key) {
        check(c.path(key).isTextual() && !c.path(key).asText().isBlank() && c.path(key).asText().length()<=1000,key,"must be a nonblank string of at most 1000 characters"); return c.path(key).asText().strip();
    }
    private static String optionalText(JsonNode c,String key) { return c.path(key).isMissingNode() || c.path(key).isNull()?null:text(c,key); }
    private static UUID uuid(JsonNode c,String key) { try { return UUID.fromString(text(c,key)); } catch (IllegalArgumentException e) { throw invalid(key,"must be UUID"); } }
    private static UUID optionalUuid(JsonNode c,String key) { return c.path(key).isMissingNode() || c.path(key).isNull()?null:uuid(c,key); }
    private static OffsetDateTime time(JsonNode c,String key) {
        try { var t=OffsetDateTime.parse(text(c,key)); check(t.getSecond()==0 && t.getNano()==0,key,"must have whole-minute precision and an explicit offset"); return t; }
        catch (java.time.format.DateTimeParseException e) { throw invalid(key,"must have an explicit UTC offset"); }
    }
    private static OffsetDateTime optionalTime(JsonNode c,String key) { return c.path(key).isMissingNode() || c.path(key).isNull()?null:time(c,key); }
    private static void check(boolean ok,String field,String message) { if (!ok) throw invalid(field,message); }
    private static PlanningService.InvalidRequestException invalid(String field,String message) { return new PlanningService.InvalidRequestException(field,message); }
}
