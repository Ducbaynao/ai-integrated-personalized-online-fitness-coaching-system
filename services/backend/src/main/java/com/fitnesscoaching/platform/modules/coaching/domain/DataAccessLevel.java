package com.fitnesscoaching.platform.modules.coaching.domain;

public enum DataAccessLevel {
    VIEW(0),
    CONTRIBUTE(1),
    MANAGE(2);

    private final int rank;

    DataAccessLevel(int rank) {
        this.rank = rank;
    }

    public boolean satisfies(DataAccessLevel required) {
        return required != null && rank >= required.rank;
    }
}
