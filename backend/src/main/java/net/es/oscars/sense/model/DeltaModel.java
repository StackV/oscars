package net.es.oscars.sense.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DeltaModel {
    private String id; // A UUID uniquely identifying the topology model delta resource.
    private String href; // A URI reference to the resource.

    @Builder.Default
    private String lastModified = "1970-01-01T00:00:00Z"; // The xsd:dateTime formatted date and time (ISO 8601) with
                                                          // time zone specified representing the time of the creation,
                                                          // last modification, or state transition of the delta
                                                          // resource.
    private String modelId; // The UUID of the root model version to which this delta has been applied.
    private DeltaState state; // The current state of the delta resource. Will contain one of Accepting,
                              // Accepted, Committing, Committed, Activating, Activated, or Failed.
    private String reduction; // The gzipped and base64 encoded delta reduction for topology model resource
                              // specified by modelId.
    private String addition; // The gzipped and base64 encoded delta addition for topology model resource
                             // specified by modelId.
    private String result; // The gzipped and base64 encoded resulting topology model that will be created
                           // by this delta resource.
}
