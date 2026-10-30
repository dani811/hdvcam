package com.hdvcam.core;

/** Modes surfaced by the original ATV/QZ/YZ camera screen layouts. */
public enum CameraMode {
    NORMAL_VIDEO("mode0_rec", "Grabación", "MODE0_VIDEO; vídeo 16:9; rcd_stop_ac"),
    SLOW_VIDEO("mode1_slow_rec", "Vídeo en cámara lenta", "MODE1_VIDEO_SLOW; vídeo 16:9"),
    LOOP_VIDEO("mode2_loop_rec", "Grabación en bucle", "MODE2_VIDEO_LOOP; vídeo 16:9"),
    TIMELAPSE_VIDEO("mode3_timelapse_rec", "Grabación time-lapse", "MODE3_VIDEO_TIMELAPSE; vídeo 16:9"),
    PHOTO("mode4_pic", "Tomar foto", "MODE4_PHOTO; vista 4:3; start_pic_start"),
    AUTO_PHOTO("mode5_auto_pic", "Foto automática", "MODE5_PHOTO_AUTO; vista 4:3"),
    BURST_PHOTO("mode6_continuous_pic", "Modo ráfaga", "MODE6_PHOTO_BLURT; vista 4:3; acción por cerrar"),
    TIMER_PHOTO("mode7_time_pic", "Foto con temporizador", "MODE7_PHOTO_TIMER; vista 4:3");

    private final String layoutId;
    private final String label;
    private final String action;

    CameraMode(String layoutId, String label, String action) {
        this.layoutId = layoutId;
        this.label = label;
        this.action = action;
    }

    public String layoutId() { return layoutId; }
    public String label() { return label; }
    public String action() { return action; }
}
