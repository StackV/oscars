package net.es.oscars.sense;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import net.es.oscars.sense.model.SENSEModel;
import net.es.oscars.topo.pop.ConsistencyException;
import net.es.oscars.topo.svc.TopoService;

@RestController
@Slf4j
public class SENSEController {
    @Autowired
    private SENSEService senseService;

    @Autowired
    private TopoService topoService;

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

    @RequestMapping(value = "/api/sense/models", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public void retrieveModel(HttpServletRequest request, HttpServletResponse res)
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

    @RequestMapping(value = "/api/sense/repo", method = RequestMethod.GET)
    @ResponseBody
    @Transactional
    public List<SENSEModel> testRepoGet() throws ConsistencyException, StartupException {
        this.startupCheck();
        return senseService.pilotRetrieve();
    }

    @RequestMapping(value = "/api/sense/repo", method = RequestMethod.PUT)
    @ResponseBody
    @Transactional
    public void testRepoPut() throws ConsistencyException, StartupException {
        this.startupCheck();
        senseService.pilotAdd();
    }

    @RequestMapping(value = "/api/sense/repo", method = RequestMethod.DELETE)
    @ResponseBody
    @Transactional
    public void testRepoDelete() throws ConsistencyException, StartupException {
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