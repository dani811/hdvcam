package com.hdvcam.protocol.aw;

/** Small Annex-B H.264 framing checks shared by the AW receiver and preview. */
public final class AnnexB {
    private AnnexB() { }

    /** Returns true when bytes contain a start code followed by a plausible H.264 NAL header. */
    public static boolean containsPlausibleNal(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        for (int i = 0; i + 2 < bytes.length; i++) {
            int header;
            if (bytes[i] != 0 || bytes[i + 1] != 0) continue;
            if (bytes[i + 2] == 1) header = i + 3;
            else if (i + 3 < bytes.length && bytes[i + 2] == 0 && bytes[i + 3] == 1) header = i + 4;
            else continue;
            if (header < bytes.length) {
                int nalType = bytes[header] & 0x1f;
                if (nalType >= 1 && nalType <= 23) return true;
            }
        }
        return false;
    }
}
