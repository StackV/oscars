/*
 * SENSE Resource Manager (SENSE-RM) Copyright (c) 2016, The Regents
 * of the University of California, through Lawrence Berkeley National
 * Laboratory (subject to receipt of any required approvals from the
 * U.S. Dept. of Energy).  All rights reserved.
 *
 * If you have questions about your rights to use or distribute this
 * software, please contact Berkeley Lab's Innovation & Partnerships
 * Office at IPO@lbl.gov.
 *
 * NOTICE.  This Software was developed under funding from the
 * U.S. Department of Energy and the U.S. Government consequently retains
 * certain rights. As such, the U.S. Government has been granted for
 * itself and others acting on its behalf a paid-up, nonexclusive,
 * irrevocable, worldwide license in the Software to reproduce,
 * distribute copies to the public, prepare derivative works, and perform
 * publicly and display publicly, and to permit other to do so.
 *
 */
package net.es.oscars.sense.model.entities;

import java.io.Serializable;

import javax.persistence.Basic;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.es.oscars.sense.model.DeltaState;

/**
 * A delta object for storage.
 * 
 * @author hacksaw
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
@Entity
@Table(name = "sensedelta")
public class SENSEDelta implements Serializable {
    @Id
    @Basic(optional = false)
    private String uuid; // The unique uuid identifying the delta within the RM.

    @Basic(optional = false)
    @Builder.Default
    private long lastModified = 0; // Representing the time of the creation, last modification, or _state
                                   // transition of the delta resource.

    @Basic(optional = false)
    private String modelId; // The UUID of the root model version to which this delta has been applied.

    @Basic(optional = false)
    private DeltaState _state; // The current _state of the delta resource. Will contain one of Accepting,
                               // Accepted, Committing, Committed, Activating, Activated, or Failed.

    private String stateDescription;

    private String[] commits;

    private String[] terminates;

    @Lob
    @Basic(fetch = FetchType.LAZY, optional = true)
    private String reduction; // The delta reduction for topology model resource specified by modelId.

    @Lob
    @Basic(fetch = FetchType.LAZY, optional = true)
    private String addition; // The delta addition for topology model resource specified by modelId.

    @Lob
    @Basic(fetch = FetchType.LAZY, optional = true)
    private String _result; // resulting topology model that will be created by this delta resource.

    public DeltaState getState() {
        return _state;
    }

    public String getResult() {
        return _result;
    }

    public void setState(DeltaState state) {
        this._state = state;
    }

    public void setResult(String result) {
        this._result = result;
    }
}
