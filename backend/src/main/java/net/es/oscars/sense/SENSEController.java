package net.es.oscars.sense;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import net.es.oscars.app.Startup;
import net.es.oscars.app.exc.StartupException;
import net.es.oscars.sense.db.SENSEDeltaRepository;
import net.es.oscars.sense.db.SENSEModelRepository;
import net.es.oscars.sense.model.DeltaModel;
import net.es.oscars.sense.model.DeltaRequest;
import net.es.oscars.sense.model.DeltaState;
import net.es.oscars.sense.model.api.DeltaErrorResponse;
import net.es.oscars.sense.model.api.DeltaPushResponse;
import net.es.oscars.sense.model.api.DeltaStatusResponse;
import net.es.oscars.sense.model.entities.SENSEDelta;
import net.es.oscars.sense.model.entities.SENSEModel;
import net.es.oscars.sense.tools.UrlHelper;
import net.es.oscars.topo.pop.ConsistencyException;

@RestController
@Slf4j
// @PreAuthorize("hasAuthority('SENSE_CLIENT')")
public class SENSEController {
    @Autowired
    private SENSEService senseService;

    @Autowired
    private SENSEDeltaRepository deltaRepo;

    @Autowired
    private SENSEModelRepository modelRepo;

    @Autowired
    private Startup startup;

    @ExceptionHandler(StartupException.class)
    @ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE)
    public void handleStartup(StartupException ex) {
        log.warn("Still in startup");
    }

    @RequestMapping(value = "/api/sense/models", method = RequestMethod.GET)
    @Transactional
    public void retrieveModel(HttpServletRequest request, HttpServletResponse res,
            @RequestParam(defaultValue = "false") boolean current, @RequestParam(defaultValue = "true") boolean summary,
            @RequestParam(defaultValue = "true") boolean encode)
            throws ConsistencyException, StartupException, IOException {
        this.startupCheck();
        long ifModifiedSince = request.getDateHeader("If-Modified-Since");
        res.setHeader("Cache-Control", "max-age=3600");

        // long lastModified =
        // topoService.latestVersion().get().getUpdated().getEpochSecond() * 1000;
        long lastModified = -1;
        long timePassed = -1;

        Optional<SENSEModel> latest = modelRepo.findFirstByOrderByCreationTimeDesc();
        if (latest.isPresent()) {
            String timeStr = latest.get().getCreationTime();
            Instant time = ZonedDateTime.parse(timeStr, DateTimeFormatter.ISO_DATE_TIME).toInstant();
            lastModified = time.toEpochMilli() * 1000;
            timePassed = Duration.between(time, Instant.now()).toMillis() / 1000;
        }

        // if request did not set the header, we get a -1 in iMS
        if (lastModified != -1 && ifModifiedSince != -1 && (timePassed < 120 || lastModified <= ifModifiedSince)) {
            log.debug("[SENSEController] retrieveModel || Returning not-modified to browser, ims: {}, lm: {}",
                    ifModifiedSince, lastModified);
            res.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        SENSEModel model = senseService.buildModel();
        ObjectMapper mapper = new ObjectMapper();
        List<SENSEModel> ret = new ArrayList<>();
        ret.add(model);

        res.setContentType("application/json");
        PrintWriter out = res.getWriter();
        out.println(mapper.writeValueAsString(ret));
        out.close();
    }

    @RequestMapping(value = "/api/sense/deltas", method = RequestMethod.POST)
    @Transactional
    public DeltaPushResponse pushDelta(@RequestBody DeltaRequest deltaRequest, HttpServletRequest req,
            HttpServletResponse res, @RequestParam(defaultValue = "true") boolean summary) throws StartupException {
        this.startupCheck();

        String location = req.getRequestURL().toString();
        log.info("[SENSEController] POST DELTAS || deltaId = {}, modelId = {}", deltaRequest.getId(),
                deltaRequest.getModelId());

        // If the requester did not specify a delta id then we need to create one.
        if (Strings.isNullOrEmpty(deltaRequest.getId())) {
            deltaRequest.setId("urn:uuid:" + UUID.randomUUID().toString());
            log.info("[SenseRmController] assigning delta id = {}", deltaRequest.getId());
        } else {
            // If delta exists, return error.
            // Optional<SENSEDelta> delta = deltaRepo.findByUuid(deltaRequest.getId());
            // if (delta.isPresent()) {
            // res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            // return ret.error("Delta " + deltaRequest.getId() + " has already been
            // pushed.").build();
            // }
        }

        try {
            // Propagate the requested delta.
            DeltaModel delta = senseService.propagateDelta(deltaRequest, null);
            if (delta == null) {
                res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return new DeltaPushResponse(null, null, "No delta returned from propagate push.");
            }

            log.info("[SenseRmController] Delta id = {}, state = {}", delta.getId(), delta.getState());
            String contentLocation = UrlHelper.append(location, delta.getId());
            log.info("[SenseRmController] Delta id = {}, lastModified = {}, content-location = {}", delta.getId(),
                    delta.getLastModified(), contentLocation);

            long lastModified = LocalDateTime.parse(delta.getLastModified(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZone(ZoneId.systemDefault()).toEpochSecond() * 1000;
            delta.setHref(contentLocation);

            log.info("[SenseRmController] Delta returning id = {}, creationTime = {}", delta.getId(),
                    delta.getLastModified());

            // return new ResponseEntity<>(delta, headers, HttpStatus.CREATED);
            res.setHeader(HttpHeaders.CONTENT_LOCATION, contentLocation);
            res.setDateHeader(HttpHeaders.LAST_MODIFIED, lastModified);
            res.setStatus(HttpServletResponse.SC_CREATED);
            return new DeltaPushResponse(delta.getState(), summary ? null : delta, null);
        } catch (Exception ex) {
            log.error("[SenseRmController] propagateDelta returning error:\n{}", ex);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return new DeltaPushResponse(null, null, ex.getMessage());
        }
    }

    @RequestMapping(value = "/api/sense/deltas/{id}/actions/commit", method = RequestMethod.PUT)
    @Transactional
    public ResponseEntity<DeltaErrorResponse> commitDelta(@PathVariable String id) throws StartupException {
        // ID required.
        if (id == null || id.isEmpty()) {
            DeltaErrorResponse err = new DeltaErrorResponse("bad_request", "No delta id specified.", null);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }

        // Retrieve delta and associated connections.
        try {
            SENSEDelta delta = deltaRepo.findByUuid(id).get();
            if (!delta.getState().equals(DeltaState.Accepted)) {
                DeltaErrorResponse err = new DeltaErrorResponse("bad_state",
                        "Delta " + id + " not in ACCEPTED state. Is instead " + delta.getState(), null);
                return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(err);
            }
            DeltaErrorResponse err = senseService.commitDelta(delta);
            if (err == null) {
                return ResponseEntity.ok().build();
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
            }
        } catch (NoSuchElementException ex) {
            DeltaErrorResponse err = new DeltaErrorResponse("not_found", "No delta " + id + " found.", null);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
        }
    }

    @RequestMapping(value = "/api/sense/deltas/{id}/actions/release", method = RequestMethod.PUT)
    @Transactional
    public void releaseDelta(@PathVariable String id, HttpServletResponse res) throws StartupException {
        // ID required.
        if (id == null || id.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // Retrieve delta and associated connections.
        try {
            SENSEDelta delta = deltaRepo.findByUuid(id).get();
            senseService.releaseDelta(delta);

            return;
        } catch (NoSuchElementException ex) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
    }

    @RequestMapping(value = "/api/sense/deltas/{id}", method = RequestMethod.GET)
    public DeltaStatusResponse getDeltaStatus(@PathVariable String id, HttpServletResponse res,
            @RequestParam(defaultValue = "true") boolean summary) {
        // ID required.
        if (id == null || id.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return new DeltaStatusResponse(null, null, "No id specified.");
        }

        try {
            SENSEDelta delta = deltaRepo.findByUuid(id).get();
            return new DeltaStatusResponse(delta.getState().name(), delta.getStateDescription(), null);
        } catch (NoSuchElementException ex) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return new DeltaStatusResponse(null, null, "Delta " + id + " not found.");
        }
    }

    @RequestMapping(value = "/api/sense/deltas/", method = RequestMethod.GET)
    public List<SENSEDelta> getDeltaUUIDs(HttpServletResponse res) {
        return deltaRepo.findAll();
    }

    //
    // DEV ENDPOINTS

    @RequestMapping(value = "/api/dev/sense/test", method = RequestMethod.GET)
    @Transactional
    public Map<String, String> testEndpoint() throws ConsistencyException, StartupException {
        this.startupCheck();
        HashMap<String, String> ret = new HashMap<>();
        ret.put("message", "Hello world!");
        ret.put("time", Instant.now().toString());

        return ret;
    }

    @RequestMapping(value = "/protected/dev/sense/test", method = RequestMethod.GET)
    @Transactional
    public Map<String, String> testProtectedEndpoint(Authentication authentication)
            throws ConsistencyException, StartupException {
        this.startupCheck();
        HashMap<String, String> ret = new HashMap<>();
        String username = authentication.getName();
        ret.put("message", "Hello world!");
        ret.put("user", username);
        ret.put("time", Instant.now().toString());

        return ret;
    }

    private void startupCheck() throws StartupException {
        if (startup.isInStartup()) {
            throw new StartupException("OSCARS starting up");
        } else if (startup.isInShutdown()) {
            throw new StartupException("OSCARS shutting down");
        }

    }

}