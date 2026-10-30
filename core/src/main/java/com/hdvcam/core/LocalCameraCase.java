package com.hdvcam.core;

import java.util.Optional;

/** Local camera-route families detected by the original app after joining Wi-Fi. */
public enum LocalCameraCase {
    MSTAR("192.168.1.1", "MStar", "CamConAtv"),
    QZ("192.168.10.1", "QZ", "CamConQZAtv"),
    YZ("192.168.169.1", "YZ", "CamConYZAtv");

    private final String routeAddress;
    private final String label;
    private final String screenAlias;

    LocalCameraCase(String routeAddress, String label, String screenAlias) {
        this.routeAddress = routeAddress;
        this.label = label;
        this.screenAlias = screenAlias;
    }

    public String routeAddress() { return routeAddress; }
    public String label() { return label; }
    public String screenAlias() { return screenAlias; }

    public static Optional<LocalCameraCase> fromRouteAddress(String address) {
        if (address == null) return Optional.empty();
        for (LocalCameraCase cameraCase : values()) {
            if (cameraCase.routeAddress.equals(address.trim())) return Optional.of(cameraCase);
        }
        return Optional.empty();
    }
}
