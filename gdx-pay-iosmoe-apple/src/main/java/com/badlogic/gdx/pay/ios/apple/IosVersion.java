package com.badlogic.gdx.pay.ios.apple;

import apple.uikit.UIDevice;

enum IosVersion {
    ;

    private static String systemVersionString;

    private static Integer majorSystemVersion;
    private static Integer minorSystemVersion;
    private static Integer patchSystemVersion;

    static boolean is_7_0_orAbove() {

        getSystemVersion();
        return majorSystemVersion >= 7;
    }

    static boolean is_11_2_orAbove() {
        getSystemVersion();
        return ((majorSystemVersion  == 11 && minorSystemVersion >= 2)  || majorSystemVersion > 11);
    }

    private static void getSystemVersion() {
        String version = UIDevice.currentDevice().systemVersion();
        systemVersionString = version;

        if (version != null) {
            String[] parts = version.split("\\.");
            if (parts.length > 0) majorSystemVersion = Integer.valueOf(parts[0]);
            if (parts.length > 1) minorSystemVersion = Integer.valueOf(parts[1]);
            if (parts.length > 2) patchSystemVersion = Integer.valueOf(parts[2]);
        } else {
            // Default to minimum OS version that RoboVM supports.
            majorSystemVersion = 6;
        }
    }

}