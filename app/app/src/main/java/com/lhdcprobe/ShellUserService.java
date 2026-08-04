package com.lhdcprobe;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku 用户服务：由 Shizuku 启动器直接 new 出来并当作 IBinder 使用，
 * 进程以 shell 身份运行。这里只实现一个事务：执行 shell 命令。
 */
public class ShellUserService extends Binder {

    public static final int CMD_EXEC = 1;

    public ShellUserService() {
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == CMD_EXEC) {
            String cmd = data.readString();
            reply.writeString(exec(cmd));
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    private static String exec(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            p.waitFor();
        } catch (Throwable t) {
            sb.append("ERR:").append(t);
        }
        return sb.toString().trim();
    }
}
