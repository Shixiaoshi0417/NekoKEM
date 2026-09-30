package com.shixiaoshi0417.nekokem;

import android.content.res.Configuration;
import android.os.LocaleList;
import java.lang.reflect.Method;

/** Test APK only: invoked by app_process as the disposable emulator's root user. */
public final class SystemLocaleControl {
    private SystemLocaleControl() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected a system locale");
        Class<?> activityManager = Class.forName("android.app.ActivityManager");
        Object service = activityManager.getMethod("getService").invoke(null);
        Class<?> managerInterface = Class.forName("android.app.IActivityManager");
        Method getConfiguration = managerInterface.getMethod("getConfiguration");
        Configuration configuration = new Configuration(
                (Configuration) getConfiguration.invoke(service));
        configuration.setLocales(LocaleList.forLanguageTags(arguments[0]));
        Configuration.class.getField("userSetLocale").setBoolean(configuration, true);
        managerInterface.getMethod("updatePersistentConfiguration", Configuration.class)
                .invoke(service, configuration);
        Configuration updated = (Configuration) getConfiguration.invoke(service);
        if (!updated.getLocales().get(0).getLanguage().equals(
                LocaleList.forLanguageTags(arguments[0]).get(0).getLanguage())) {
            throw new IllegalStateException("System configuration was not updated");
        }
        System.out.println("systemLocales=" + updated.getLocales().toLanguageTags());
    }
}
