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
package net.es.oscars.sense.tools;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

import net.es.oscars.sense.definitions.Mrs;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
/**
 *
 * @author xyang
 */
public class RdfOwl {

    private static Model m_model = ModelFactory.createDefaultModel();

    public static final String rdfNS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    public static String getRdfURI() {
        return rdfNS;
    }

    public static final String rdfsNS = "http://www.w3.org/2000/01/rdf-schema#";

    public static String getRdfsURI() {
        return rdfsNS;
    }

    public static final String owlNS = "http://www.w3.org/2002/07/owl#";

    public static String getOwlURI() {
        return owlNS;
    }

    public static final String xsdNS = "http://www.w3.org/2001/XMLSchema#";

    public static String getXsdURI() {
        return xsdNS;
    }

    public static final Property type = m_model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
    public static final Resource NamedIndividual = m_model
            .createResource("http://www.w3.org/2002/07/owl#NamedIndividual");
    public static final Resource labelTypeVLAN = m_model
            .createResource("http://schemas.ogf.org/nml/2012/10/ethernet#vlan");

    public static Resource createResource(OntModel model, String uri, Resource type) {
        Resource res = model.createResource(uri);
        // model.add(model.createStatement(res, RdfOwl.type, RdfOwl.NamedIndividual));
        model.add(model.createStatement(res, RdfOwl.type, type));
        return res;
    }

    public static Resource createResourceUnverifiable(OntModel model, String uri, Resource type) {
        Resource res = model.createResource(uri);
        model.add(model.createStatement(res, RdfOwl.type, type));
        model.add(model.createStatement(res, Mrs.type, "unverifiable"));
        return res;
    }
}
