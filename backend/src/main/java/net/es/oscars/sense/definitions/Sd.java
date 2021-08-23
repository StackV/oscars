/*
 * Copyright (c) 2013-2016 University of Maryland
 * Created by: Xi Yang 2014

 * Permission is hereby granted, free of charge, to any person obtaining a copy 
 * of this software and/or hardware specification (the “Work”) to deal in the 
 * Work without restriction, including without limitation the rights to use, 
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of 
 * the Work, and to permit persons to whom the Work is furnished to do so, 
 * subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in 
 * all copies or substantial portions of the Work.

 * THE WORK IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL 
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING 
 * FROM, OUT OF OR IN CONNECTION WITH THE WORK OR THE USE OR OTHER DEALINGS  
 * IN THE WORK.
 */

package net.es.oscars.sense.definitions;

import org.apache.jena.rdf.model.*;

/**
 *
 * @author onos-stackv
 */
public class Sd {

    private static Model m_model = ModelFactory.createDefaultModel();

    public static final String NS = "http://schemas.ogf.org/nsi/2013/12/services/definition#";

    public static String getURI() {
        return NS;
    }

    public static final Resource NAMESPACE = m_model.createResource(NS);

    public static final Resource ServiceDefinition = m_model
            .createResource("http://schemas.ogf.org/nsi/2013/12/services/definition#ServiceDefinition");
    public static final Property hasServiceDefinition = m_model
            .createProperty("http://schemas.ogf.org/nsi/2013/12/services/definition#hasServiceDefinition");
    public static final Property serviceType = m_model
            .createProperty("http://schemas.ogf.org/nsi/2013/12/services/definition#serviceType");

    public static final String URI_SvcDef_L2P2pEs = "http://services.ogf.org/nsi/2018/06/descriptions/l2-p2p-es";
    public static final String URI_SvcDef_L2MpEs = "http://services.ogf.org/nsi/2018/06/descriptions/l2-mp-es";
    // public static final String URI_SvcDef_L2MpEs =
    // "http://services.ogf.org/nsi/2013/12/descriptions/EVTS.A-GOLE";
    public static final String URI_SvcDef_L3Vpn = "http://services.ogf.org/nsi/2019/08/descriptions/l3-vpn";

}
