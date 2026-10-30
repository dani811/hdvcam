package com.hdvcam.core;

import java.util.Arrays;
import java.util.List;

/** Recovered setting dispatch keys from B0.d and V.e.setSetValue. */
public enum CameraSetting {
    VIDEO_RESOLUTION("video_resolution", "Resolución de vídeo", "setModeResolutionValue(0,index)"),
    RECORD_SOUND("record_sound", "Sonido de grabación", "setRecordSoundOnOff(index)"),
    VIDEO_TIMELAPSE("video_delayp", "Lapso de vídeo", "setRecordLapseTimeValue(index)"),
    VIDEO_LENGTH("video_time_length", "Duración/bucle de vídeo", "setRecordLoopTimeValue(index)"),
    VIDEO_SLOW("video_slowp", "Vídeo lento", "setModeResolutionValue(1,index)"),
    PHOTO_RESOLUTION("photo_resolution", "Resolución de foto", "setModeResolutionValue(4,index)"),
    PHOTO_TIMER("Time_take_photos", "Temporizador de foto", "setCaptureTimerTimeValue(index)"),
    AUTO_PHOTO("Automatically_take_pictures", "Captura automática", "setCaptureAutoTimeValue(index)"),
    MOTION_PHOTO("Motion_pictures", "Captura por movimiento", "setCaptureMotionTimeValue(index)"),
    EXPOSURE("exposure", "Exposición", "setExposureValue(index)"),
    WHITE_BALANCE("white_balance", "Balance de blancos", "setWhiteBalanceValue(index)"),
    WIFI_OFF("wifionoff", "Apagar Wi-Fi", "setWifiOff()"),
    IMAGE_ROTATION("Image_rotation", "Rotación de imagen", "setImageRotationOnOff(index)"),
    LIGHT_FREQUENCY("light_freq", "Frecuencia anti-parpadeo", "setLightFreqValue(index)"),
    INDICATOR_LED("LED_lights", "LED indicador", "setIndicatedLightOnOff(index)"),
    SCREEN_SAVER("screen_switch", "Salvapantallas", "setScreenSaverValue(index)"),
    TIME_WATERMARK("time_water_mark", "Marca de fecha/hora", "setTimeWaterMarkOnOff(index)"),
    VIDEO_SENSOR_MODE("video_open_ctl", "Modo de sensor/vídeo (etiqueta incierta)", "setSensorMode(index)");

    private final String key;
    private final String label;
    private final String dispatch;

    CameraSetting(String key, String label, String dispatch) {
        this.key = key;
        this.label = label;
        this.dispatch = dispatch;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String dispatch() { return dispatch; }
    public static List<CameraSetting> catalog() { return Arrays.asList(values()); }

    public static CameraSetting fromKey(String key) {
        if (key == null) return null;
        for (CameraSetting setting : values()) if (setting.key.equals(key)) return setting;
        return null;
    }
}
