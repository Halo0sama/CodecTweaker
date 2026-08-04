package com.lhdcprobe;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 全局崩溃捕获：把异常栈写入文件，下次打开 App 时展示，方便反馈。 */
public class App extends Application {

    private static final String CRASH_FILE = "crash.txt";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File file = new File(getFilesDir(), CRASH_FILE);
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                pw.println("thread=" + thread.getName());
                throwable.printStackTrace(pw);
                pw.flush();
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(sw.toString().getBytes("UTF-8"));
                fos.close();
                Log.e("LHDCProbe", "crash captured: " + sw);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    public static File crashFile(Context context) {
        return new File(context.getFilesDir(), CRASH_FILE);
    }
}
