package com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest.RegistryDtos.CreateJourneyRequest;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest.RegistryDtos.DraftRequest;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest.RegistryDtos.JourneyDto;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest.RegistryDtos.PublishedConfigDto;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest.RegistryDtos.RejectRequest;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest.RegistryDtos.ValidationResultDto;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest.RegistryDtos.VersionDto;
import com.idfcfirstbank.integration.platform.journeyregistry.application.RegistryService;
import com.idfcfirstbank.integration.platform.journeyregistry.config.RegistryConfiguration;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyMeta;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The registry's REST face — one endpoint per {@code JourneyRepository} method
 * in the DAG Designer, plus the two engine-facing published reads. Thin by law:
 * every rule (actor required, maker != checker, single draft, validation gate)
 * lives in {@link RegistryService}; transport maps headers/paths/DTOs only.
 *
 * <p>Actor identity rides the {@code X-User-Id} header. Until the real IdP lands
 * (production gate), the header IS the identity — the point A1 proves is that the
 * SERVER enforces the rules on whatever identity transport presents (403s, not
 * disabled buttons).
 */
@RestController
@RequestMapping("/api/v1")
public class RegistryController {

    private static final String ACTOR = RegistryConfiguration.ACTOR_HEADER;

    private final RegistryService service;
    private final ObjectMapper objectMapper;

    public RegistryController(RegistryService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    // ---- journeys (designer) ---------------------------------------------------

    @GetMapping("/journeys")
    public List<JourneyDto> listJourneys(
            @RequestParam(required = false) String businessLine,
            @RequestParam(required = false) String product,
            @RequestParam(required = false) String partner) {
        return service.listJourneys().stream()
                .filter(m -> businessLine == null || businessLine.equals(m.businessLine()))
                .filter(m -> product == null || product.equals(m.product()))
                .filter(m -> partner == null || partner.equals(m.partner()))
                .map(this::journeyDto)
                .toList();
    }

    @PostMapping("/journeys")
    @ResponseStatus(HttpStatus.CREATED)
    public JourneyDto createJourney(@RequestHeader(value = ACTOR, required = false) String actor,
                                    @RequestBody CreateJourneyRequest request) {
        JourneyMeta created = service.createJourney(request.key(), request.name(),
                request.businessLine(), request.product(), request.partner(), actor);
        return JourneyDto.of(created, List.of());
    }

    @GetMapping("/journeys/{key}")
    public JourneyDto journey(@PathVariable String key) {
        return journeyDto(service.journey(key));
    }

    // ---- versions (designer maker/checker) ---------------------------------------

    @GetMapping("/journeys/{key}/versions")
    public List<VersionDto> versions(@PathVariable String key) {
        return service.versions(key).stream()
                .map(r -> VersionDto.of(r, objectMapper, false))
                .toList();
    }

    @GetMapping("/journeys/{key}/versions/{version}")
    public VersionDto version(@PathVariable String key, @PathVariable int version) {
        return VersionDto.of(service.version(key, version), objectMapper, true);
    }

    @PostMapping("/journeys/{key}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionDto createDraft(@PathVariable String key,
                                  @RequestHeader(value = ACTOR, required = false) String actor,
                                  @RequestBody DraftRequest request) {
        return VersionDto.of(service.createDraft(key, request.configJson(), request.note(), actor),
                objectMapper, true);
    }

    @PutMapping("/journeys/{key}/versions/{version}")
    public VersionDto saveDraft(@PathVariable String key, @PathVariable int version,
                                @RequestHeader(value = ACTOR, required = false) String actor,
                                @RequestBody DraftRequest request) {
        return VersionDto.of(service.saveDraft(key, version, request.configJson(), request.note(), actor),
                objectMapper, true);
    }

    /** Authoritative server-side validation (the designer's panel mirrors it live). */
    @PostMapping("/journeys/{key}/versions/{version}/validate")
    public ValidationResultDto validate(@PathVariable String key, @PathVariable int version) {
        return new ValidationResultDto(service.validate(key, version));
    }

    @PostMapping("/journeys/{key}/versions/{version}/submit")
    public VersionDto submit(@PathVariable String key, @PathVariable int version,
                             @RequestHeader(value = ACTOR, required = false) String actor) {
        return VersionDto.of(service.submit(key, version, actor), objectMapper, true);
    }

    @PostMapping("/journeys/{key}/versions/{version}/approve")
    public VersionDto approve(@PathVariable String key, @PathVariable int version,
                              @RequestHeader(value = ACTOR, required = false) String actor) {
        return VersionDto.of(service.approve(key, version, actor), objectMapper, true);
    }

    @PostMapping("/journeys/{key}/versions/{version}/reject")
    public VersionDto reject(@PathVariable String key, @PathVariable int version,
                             @RequestHeader(value = ACTOR, required = false) String actor,
                             @RequestBody(required = false) RejectRequest request) {
        return VersionDto.of(
                service.reject(key, version, request == null ? null : request.comment(), actor),
                objectMapper, true);
    }

    // ---- published reads (engine) --------------------------------------------------

    /** Bootstrap/refresh: every journey's currently-published config. */
    @GetMapping("/published-journeys")
    public List<PublishedConfigDto> publishedJourneys() {
        return service.publishedConfigs().stream()
                .map(r -> PublishedConfigDto.of(r, objectMapper))
                .toList();
    }

    /** Pinned fetch: a specific once-published version (in-flight runs stay on it). */
    @GetMapping("/published-journeys/{key}/versions/{version}")
    public PublishedConfigDto publishedJourney(@PathVariable String key, @PathVariable int version) {
        return PublishedConfigDto.of(service.publishedConfig(key, version), objectMapper);
    }

    private JourneyDto journeyDto(JourneyMeta meta) {
        List<VersionDto> versions = service.versions(meta.key()).stream()
                .map(r -> VersionDto.of(r, objectMapper, false))
                .toList();
        return JourneyDto.of(meta, versions);
    }
}
