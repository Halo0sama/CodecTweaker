package com.lhdcprobe;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/** 通过 Shizuku 用户服务以 shell 身份执行命令。 */
public class ShellExec {

    private static final String TAG = "ShellExec";
    private static volatile IBinder binder;
    private static volatile boolean bound = false;

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            bound = true;
            Log.i(TAG, "Shizuku 用户服务已连接");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            bound = false;
        }
    };

    public static void bind(Context context) {
        if (bound) return;
        if (!Shizuku.pingBinder()) {
            Log.i(TAG, "pingBinder=false");
            return;
        }
        Log.i(TAG, "checkSelfPermission=" + Shizuku.checkSelfPermission());
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(context, ShellUserService.class))
                    .processNameSuffix("shizuku");
            Shizuku.bindUserService(args, conn);
        } catch (Throwable t) {
            Log.i(TAG, "bind 失败: " + t);
        }
    }

    public static boolean available() {
        return bound && binder != null && Shizuku.pingBinder();
    }

    public static String exec(String cmd, long timeoutMs) {
        if (!available()) return "ERR:shizuku-not-ready";
        try {
            FutureTask<String> task = new FutureTask<>(() -> {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeString(cmd);
                    binder.transact(ShellUserService.CMD_EXEC, data, reply, 0);
                    String out = reply.readString();
                    return out == null ? "" : out;
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            });
            Thread t = new Thread(task, "shell-exec");
            t.start();
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            return "ERR:" + e;
        }
    }

    /** 用 uiautomator 导出当前界面 XML。 */
    public static String dumpUi() {
        return exec("uiautomator dump /sdcard/lhdc_ui.xml >/dev/null 2>&1; cat /sdcard/lhdc_ui.xml", 9000);
    }

    /** 在 XML 里找包含指定文字的节点，返回其中心坐标。 */
    public static int[] findCenter(String xml, String text) {
        if (xml == null || text == null) return null;
        try {
            Pattern p = Pattern.compile(
                    "<node[^>]*text=\"([^\"]*" + Pattern.quote(text) + "[^\"]*)\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"",
                    Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(xml);
            if (m.find()) {
                int x1 = Integer.parseInt(m.group(2));
                int y1 = Integer.parseInt(m.group(3));
                int x2 = Integer.parseInt(m.group(4));
                int y2 = Integer.parseInt(m.group(5));
                return new int[]{(x1 + x2) / 2, (y1 + y2) / 2};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 滚动直到目标文字出现，返回其中心坐标；找不到返回 null。 */
    public static int[] scrollUntilVisible(String text) {
        for (int i = 0; i < 10; i++) {
            int[] c = findCenter(dumpUi(), text);
            if (c != null) return c;
            exec("input swipe 600 2000 600 1400 350", 3000);
        }
        return null;
    }

    /** 原地等待目标文字出现（不滚动），返回中心坐标；找不到返回 null。 */
    public static int[] waitVisible(String text, int tries) {
        for (int i = 0; i < tries; i++) {
            int[] c = findCenter(dumpUi(), text);
            if (c != null) return c;
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
            }
        }
        return null;
    }

    /** 等待某个文字（弹窗内容）消失，说明弹窗已关闭；tries 次后仍可见返回 false。 */
    public static boolean waitGone(String text, int tries) {
        for (int i = 0; i < tries; i++) {
            if (findCenter(dumpUi(), text) == null) return true;
            try {
                Thread.sleep(350);
            } catch (InterruptedException ignored) {
            }
        }
        return false;
    }
}
