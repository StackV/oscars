package net.es.oscars.sense;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import net.es.oscars.app.Startup;
import net.es.oscars.app.exc.StartupException;
import net.es.oscars.resv.svc.ResvService;
import net.es.oscars.sense.model.SENSEModel;
import net.es.oscars.topo.pop.ConsistencyException;

@RestController
@Slf4j
public class SENSEController {

    @Autowired
    private SENSEService senseService;

    @Autowired
    private Startup startup;

    @ExceptionHandler(StartupException.class)
    @ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE)
    public void handleStartup(StartupException ex) {
        log.warn("Still in startup");
    }

    @RequestMapping(value = "/api/sense/test", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public Map<String, String> testEndpoint() throws ConsistencyException, StartupException {
        this.startupCheck();
        HashMap<String, String> ret = new HashMap<>();
        ret.put("message", "Hello world!");
        ret.put("time", Instant.now().toString());

        return ret;
    }

    @RequestMapping(value = "/protected/sense/test", method = RequestMethod.GET)
    @ResponseBody
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

    @RequestMapping(value = "/api/sense/model", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public String retrieveModel() throws ConsistencyException, StartupException, JsonProcessingException {
        this.startupCheck();
        // HashMap<String, String> ret = new HashMap<>();
        // ret.put("data", senseService.buildModel());
        // ret.put("time", Instant.now().toString());

        return senseService.buildModel();
    }

    @RequestMapping(value = "/api/sense/repo", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public List<SENSEModel> testRepoGet() throws ConsistencyException, StartupException, JsonProcessingException {
        this.startupCheck();
        return senseService.pilotRetrieve();
    }

    @RequestMapping(value = "/api/sense/repo", method = RequestMethod.PUT)
    @ResponseBody
    @Transactional
    public void testRepoPut() throws ConsistencyException, StartupException, JsonProcessingException {
        this.startupCheck();
        senseService.pilotAdd();
    }

    @RequestMapping(value = "/api/sense/repo", method = RequestMethod.DELETE)
    @ResponseBody
    @Transactional
    public void testRepoDelete() throws ConsistencyException, StartupException, JsonProcessingException {
        this.startupCheck();
        senseService.pilotClear();
    }

    private void startupCheck() throws StartupException {
        if (startup.isInStartup()) {
            throw new StartupException("OSCARS starting up");
        } else if (startup.isInShutdown()) {
            throw new StartupException("OSCARS shutting down");
        }

    }

}