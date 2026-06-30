package com.idfcfirstbank.integration.platform.routeconfig.adapter.in.rest;

import com.idfcfirstbank.integration.platform.routeconfig.application.RouteConfigService;
import com.idfcfirstbank.integration.platform.routeconfig.domain.CrmApiRouterEndpoint;
import com.idfcfirstbank.integration.platform.routeconfig.domain.CrmApiRouterGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** REST CRUD for the route config registry (BRD §7). */
@RestController
public class RouteConfigController {

    private final RouteConfigService service;

    public RouteConfigController(RouteConfigService service) {
        this.service = service;
    }

    @PostMapping("/create/endpoint/config")
    public CrmApiRouterEndpoint createEndpoint(@RequestBody CrmApiRouterEndpoint body) {
        return service.createEndpoint(body);
    }

    @PostMapping("/bulk/create/endpoint/config")
    public List<CrmApiRouterEndpoint> bulkCreateEndpoint(@RequestBody List<CrmApiRouterEndpoint> body) {
        return service.bulkCreateEndpoints(body);
    }

    @GetMapping("/endpoint/config")
    public List<CrmApiRouterEndpoint> endpoints() {
        return service.listEndpoints();
    }

    @PostMapping("/create/gateway/config")
    public CrmApiRouterGateway createGateway(@RequestBody CrmApiRouterGateway body) {
        return service.createGateway(body);
    }

    @GetMapping("/gateway/config")
    public List<CrmApiRouterGateway> gateways() {
        return service.listGateways();
    }

    @DeleteMapping("/delete")
    public ResponseEntity<String> delete(@RequestParam String set, @RequestParam long sno) {
        boolean removed = service.delete(set, sno);
        return removed ? ResponseEntity.ok("deleted")
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body("not found");
    }

    @ExceptionHandler(RouteConfigService.DuplicateConfigException.class)
    public ResponseEntity<String> onDuplicate(RouteConfigService.DuplicateConfigException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
