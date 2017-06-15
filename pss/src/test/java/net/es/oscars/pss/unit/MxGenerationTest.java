package net.es.oscars.pss.unit;

import lombok.extern.slf4j.Slf4j;
import net.es.oscars.dto.pss.cmd.CommandType;
import net.es.oscars.dto.topo.enums.DeviceModel;
import net.es.oscars.pss.AbstractPssTest;
import net.es.oscars.pss.beans.ConfigException;
import net.es.oscars.pss.ctg.UnitTests;
import net.es.oscars.pss.help.ParamsLoader;
import net.es.oscars.pss.help.RouterTestSpec;
import net.es.oscars.pss.svc.MxCommandGenerator;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.List;

@Slf4j
public class MxGenerationTest {

    @Autowired
    private ParamsLoader loader;

    @Autowired
    private MxCommandGenerator commandGen;


    @Category(UnitTests.class)
    public void makeMxConfigs() throws ConfigException, IOException {

        log.info("testing MX build");
        loader.loadSpecs(CommandType.BUILD);
        List<RouterTestSpec> specs = loader.getSpecs();

        for (RouterTestSpec spec : specs) {
            if (spec.getModel().equals(DeviceModel.JUNIPER_MX)) {
                if (!spec.getShouldFail()) {
                    log.info("testing "+spec.getFilename());
                    String config = commandGen.build(spec.getMxParams());
                    log.info("config generated: \n" + config);
                }
            }
        }

        log.info("testing MX dismantle");

        loader.loadSpecs(CommandType.DISMANTLE);
        specs = loader.getSpecs();

        for (RouterTestSpec spec : specs) {
            if (spec.getModel().equals(DeviceModel.JUNIPER_MX)) {
                if (!spec.getShouldFail()) {
                    log.info("testing "+spec.getFilename());
                    String config = commandGen.dismantle(spec.getMxParams());
                    log.info("config generated: \n" + config);
                }
            }
        }
        log.info("done testing MX configs");

    }

}
