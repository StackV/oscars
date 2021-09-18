package net.es.oscars.sense.model;

public enum DeltaState {
    Accepting(0), Accepted(1), Committing(2), Committed(3), Failed(4);

    private final long value;

    DeltaState(long value) {
        this.value = value;
    }

    public long value() {
        return value;
    }
}
