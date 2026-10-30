package com.hdvcam.core;

public enum Evidence {
    CONFIRMED("Confirmado"),
    PARTIAL("Parcial"),
    SIMULATED("Simulado"),
    NOT_IMPLEMENTED("Pendiente");

    private final String label;

    Evidence(String label) { this.label = label; }

    public String label() { return label; }
}
