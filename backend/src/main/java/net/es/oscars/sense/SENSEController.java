package net.es.oscars.sense;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.datatype.DatatypeConfigurationException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import net.es.oscars.sense.model.DeltaModel;
import net.es.oscars.sense.model.DeltaRequest;
import net.es.oscars.sense.model.DeltaStatusResponse;
import net.es.oscars.sense.model.entities.SENSEDelta;
import net.es.oscars.sense.model.entities.SENSEModel;
import net.es.oscars.sense.tools.UrlHelper;
import net.es.oscars.sense.tools.XmlUtilities;
import net.es.oscars.topo.pop.ConsistencyException;
import net.es.oscars.topo.svc.TopoService;

@RestController
@Slf4j
public class SENSEController {
    @Autowired
    private SENSEService senseService;

    @Autowired
    private SENSEDeltaRepository deltaRepo;

    @Autowired
    private TopoService topoService;

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
        // HashMap<String, String> ret = new HashMap<>();
        // ret.put("data", senseService.buildModel());
        // ret.put("time", Instant.now().toString());
        long ifModifiedSince = request.getDateHeader("If-Modified-Since");
        res.setHeader("Cache-Control", "max-age=3600");
        long lastModified = topoService.getCurrent().getUpdated().getEpochSecond() * 1000; // I-M-S in milliseconds

        // if request did not set the header, we get a -1 in iMS
        if (ifModifiedSince != -1 && lastModified <= ifModifiedSince) {
            // log.debug("returning not-modified to browser, ims: "+ifModifiedSince+ " lm:
            // "+lastModified);
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
    public DeltaModel pushDelta(@RequestBody DeltaRequest deltaRequest, Principal auth, HttpServletRequest req,
            HttpServletResponse res) throws StartupException {
        this.startupCheck();

        String location = req.getRequestURL().toString();
        log.info("[SENSEController] POST DELTAS || deltaId = {}, modelId = {}", deltaRequest.getId(),
                deltaRequest.getModelId());

        // If the requester did not specify a delta id then we need to create one.
        if (Strings.isNullOrEmpty(deltaRequest.getId())) {
            deltaRequest.setId("urn:uuid:" + UUID.randomUUID().toString());
            log.info("[SenseRmController] assigning delta id = {}", deltaRequest.getId());
        }

        try {
            // Propagate the requested delta.
            Optional<DeltaModel> response = senseService.propagateDelta(deltaRequest, auth.getName()).get();
            if (response == null || !response.isPresent()) {
                res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return null;
            }
            DeltaModel delta = response.get();

            log.info("[SenseRmController] Delta id = {}, state = {}", delta.getId(), delta.getState());
            String contentLocation = UrlHelper.append(location, delta.getId());
            log.info("[SenseRmController] Delta id = {}, lastModified = {}, content-location = {}", delta.getId(),
                    delta.getLastModified(), contentLocation);

            long lastModified = XmlUtilities.xmlGregorianCalendar(delta.getLastModified()).toGregorianCalendar()
                    .getTimeInMillis();

            delta.setHref(contentLocation);

            log.info("[SenseRmController] Delta returning id = {}, creationTime = {}", delta.getId(),
                    delta.getLastModified());

            // return new ResponseEntity<>(delta, headers, HttpStatus.CREATED);
            res.setHeader(HttpHeaders.CONTENT_LOCATION, contentLocation);
            res.setDateHeader(HttpHeaders.LAST_MODIFIED, lastModified);
            return delta;
        } catch (InterruptedException | ExecutionException | IOException | DatatypeConfigurationException ex) {
            log.error("pullModel failed", ex);
            // Error error =
            // Error.builder().error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
            // .error_description(ex.getMessage()).build();
            log.error("[SenseRmController] propagateDelta returning error:\n{}", ex);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return null;
        }
    }

    @RequestMapping(value = "/api/sense/deltas/{id}/actions/commit", method = RequestMethod.PUT)
    @Transactional
    public void commitDelta(@PathVariable String id, HttpServletResponse res) throws StartupException {
        // ID required.
        if (Strings.isNullOrEmpty(id)) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // Retrieve delta and associated connections.
        try {
            SENSEDelta delta = deltaRepo.findByUuid(id).get();
            senseService.commitDelta(delta);
        } catch (NoSuchElementException ex) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
    }

    @RequestMapping(value = "/api/sense/deltas/{id}", method = RequestMethod.GET)
    public DeltaStatusResponse getDeltaStatus(@PathVariable String id, HttpServletResponse res,
            @RequestParam(defaultValue = "true") boolean summary) {
        // ID required.
        if (Strings.isNullOrEmpty(id)) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }

        try {
            SENSEDelta delta = deltaRepo.findByUuid(id).get();
            return DeltaStatusResponse.builder().state(delta.getState().name()).build();
        } catch (NoSuchElementException ex) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return null;
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