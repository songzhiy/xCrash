// Copyright (c) 2019-present, iQIYI, Inc. All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//

// Created by caikelun on 2019-09-03.
package xcrash;

import android.content.Context;
import android.os.Build;
import android.os.FileObserver;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.os.FileObserver.CLOSE_WRITE;

/**
 * 本类只针对在 android 21以下
 * 核心思想：通过监控data/anr/目录 看是否有文件写入
 * 当有文件写入时 说明发生了anr 然后进行捕捉和分析
 */
@SuppressWarnings("StaticFieldLeak")
class AnrHandler {

    private static final AnrHandler instance = new AnrHandler();

    private final Date startTime = new Date();
    //格式为：----- pid (进程号) at (发生时间) ----- 这个格式应该是android记录的anr格式 按照这个格式解析即可
    private final Pattern patPidTime = Pattern.compile("^-----\\spid\\s(\\d+)\\sat\\s(.*)\\s-----$");
    //格式为: Cmd line:
    private final Pattern patProcessName = Pattern.compile("^Cmd\\sline:\\s+(.*)$");
    private final long anrTimeoutMs = 15 * 1000;

    private Context ctx;
    private int pid;
    private String processName;
    private String appId;
    private String appVersion;
    private String logDir;
    private boolean checkProcessState;
    private int logcatSystemLines;
    private int logcatEventsLines;
    private int logcatMainLines;
    private boolean dumpFds;
    private boolean dumpNetworkInfo;
    private ICrashCallback callback;
    private long lastTime = 0;
    private FileObserver fileObserver = null;

    private AnrHandler() {
    }

    static AnrHandler getInstance() {
        return instance;
    }

    /**
     * 初始化AnrHandler
     * 构建一个FileObserver 监听 data/anr目录
     * @param ctx
     * @param pid
     * @param processName
     * @param appId
     * @param appVersion
     * @param logDir
     * @param checkProcessState
     * @param logcatSystemLines
     * @param logcatEventsLines
     * @param logcatMainLines
     * @param dumpFds
     * @param dumpNetworkInfo
     * @param callback
     */
    @SuppressWarnings("deprecation")
    void initialize(Context ctx, int pid, String processName, String appId, String appVersion, String logDir,
                    boolean checkProcessState, int logcatSystemLines, int logcatEventsLines, int logcatMainLines,
                    boolean dumpFds, boolean dumpNetworkInfo, ICrashCallback callback) {

        //check API level
        if (Build.VERSION.SDK_INT >= 21) {
            return;
        }

        this.ctx = ctx;
        this.pid = pid;
        this.processName = (TextUtils.isEmpty(processName) ? "unknown" : processName);
        this.appId = appId;
        this.appVersion = appVersion;
        this.logDir = logDir;
        this.checkProcessState = checkProcessState;
        this.logcatSystemLines = logcatSystemLines;
        this.logcatEventsLines = logcatEventsLines;
        this.logcatMainLines = logcatMainLines;
        this.dumpFds = dumpFds;
        this.dumpNetworkInfo = dumpNetworkInfo;
        this.callback = callback;

        fileObserver = new FileObserver("/data/anr/", CLOSE_WRITE) {
            public void onEvent(int event, String path) {
                try {
                    if (path != null) {
                        String filepath = "/data/anr/" + path;
                        if (filepath.contains("trace")) {
                            handleAnr(filepath);
                        }
                    }
                } catch (Exception e) {
                    XCrash.getLogger().e(Util.TAG, "AnrHandler fileObserver onEvent failed", e);
                }
            }
        };

        try {
            fileObserver.startWatching();
        } catch (Exception e) {
            fileObserver = null;
            XCrash.getLogger().e(Util.TAG, "AnrHandler fileObserver startWatching failed", e);
        }
    }

    void notifyJavaCrashed() {
        if (fileObserver != null) {
            try {
                fileObserver.stopWatching();
            } catch (Exception e) {
                XCrash.getLogger().e(Util.TAG, "AnrHandler fileObserver stopWatching failed", e);
            } finally {
                fileObserver = null;
            }
        }
    }

    /**
     *
     * @param filepath
     */
    private void handleAnr(String filepath) {
        Date anrTime = new Date();

        //check ANR time interval
        //简单check一下时间，如果发生anr的时间 是最近产生的 不重复记录 时间阀值15秒
        if (anrTime.getTime() - lastTime < anrTimeoutMs) {
            return;
        }

        //check process error state
        //如果增加了进程状态校验 此时需要去check一下当前的系统中 是否真正发生了 not response的情况
        if (this.checkProcessState) {
            if (!Util.checkProcessAnrState(this.ctx, anrTimeoutMs)) {
                return;
            }
        }

        //get trace
        //从data/anr中通知到的文件中捕获相关anr信息
        //需要check是否是当前进程 通过pid / pname
        //同时check一下日志记录时间 和 上面的anrTime是否在阈值15秒范围内
        String trace = getTrace(filepath, anrTime.getTime());
        if (TextUtils.isEmpty(trace)) {
            return;
        }

        //captured ANR
        //由于已经捕捉到了本次anr，更新一下上次发生anr的时间
        lastTime = anrTime.getTime();

        //delete extra ANR log files
        //通过FileManager看下anr日志情况，主要进行如下操作：
        //查看anr日志数量 如果超过了允许范围 则进行整理与删除 同crash文件一致 直接delete/转换成placeholder文件
        if (!FileManager.getInstance().maintainAnr()) {
            return;
        }

        //get emergency
        String emergency = null;
        try {
            //与java crash一致 捕获相关的设备信息作为头部 & 添加上anr相关日志
            emergency = getEmergency(anrTime, trace);
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "AnrHandler getEmergency failed", e);
        }

        //下面的流程实际上和java crash的捕捉信息都一致了
        //主要进行了几个操作：
        //1、写入设备信息 + anr相关日志
        //2、记录logcat信息
        //3、记录打开文件信息
        //4、记录网络信息
        //5、记录内存信息
        //6、回调上层接口 表明发生了anr异常
        //create log file
        File logFile = null;
        try {
            String logPath = String.format(Locale.US, "%s/%s_%020d_%s__%s%s", logDir, Util.logPrefix, anrTime.getTime() * 1000, appVersion, processName, Util.anrLogSuffix);
            logFile = FileManager.getInstance().createLogFile(logPath);
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "AnrHandler createLogFile failed", e);
        }

        //write info to log file
        if (logFile != null) {
            RandomAccessFile raf = null;
            try {
                raf = new RandomAccessFile(logFile, "rws");

                //write emergency info
                if (emergency != null) {
                    raf.write(emergency.getBytes("UTF-8"));
                }

                //If we wrote the emergency info successfully, we don't need to return it from callback again.
                emergency = null;

                //write logcat
                if (logcatMainLines > 0 || logcatSystemLines > 0 || logcatEventsLines > 0) {
                    raf.write(Util.getLogcat(logcatMainLines, logcatSystemLines, logcatEventsLines).getBytes("UTF-8"));
                }

                //write fds
                if (dumpFds) {
                    raf.write(Util.getFds().getBytes("UTF-8"));
                }

                //write network info
                if (dumpNetworkInfo) {
                    raf.write(Util.getNetworkInfo().getBytes("UTF-8"));
                }

                //write memory info
                raf.write(Util.getMemoryInfo().getBytes("UTF-8"));
            } catch (Exception e) {
                XCrash.getLogger().e(Util.TAG, "AnrHandler write log file failed", e);
            } finally {
                if (raf != null) {
                    try {
                        raf.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        //callback
        if (callback != null) {
            try {
                callback.onCrash(logFile == null ? null : logFile.getAbsolutePath(), emergency);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 获取设备相关信息 同时将anr异常栈进行格式化处理
     * @param anrTime
     * @param trace
     * @return
     */
    private String getEmergency(Date anrTime, String trace) {
        return Util.getLogHeader(startTime, anrTime, Util.anrCrashType, appId, appVersion)
            + "pid: " + pid + "  >>> " + processName + " <<<\n"
            + "\n"
            + Util.sepOtherThreads
            + "\n"
            + trace
            + "\n"
            + Util.sepOtherThreadsEnding
            + "\n\n";
    }

    /**
     * 从data/anr文件中捕获相关anr信息
     *
     * 由于data/anr文件夹下会存放所有的anr信息，所以我们需要针对anr文件的特有格式，优先获取到如下信息：
     * 1、pid + 记录的时间
     * 2、pname
     *
     * 然后进行相关条件check：
     * 1、判断是否pid和当前进程pid相同 看是不是同一个app
     * 2、判断日志记录时间与anr发生时我们anrhandler记录的时间 是否在15秒阈值范围内 如果不在 可能是旧文件 直接跳过（为啥会触发到旧文件？）
     * 3、判断是否pname与当前进程pname相同 看是不是同一个进程
     *
     * 当都满足以上条件时 代表找到了这次的anr日志 我们只需要以此读取文件内容 然后记录到stringbuilder中即可
     *
     * @param filepath
     * @param anrTime
     * @return
     */
    private String getTrace(String filepath, long anrTime) {

        // "\n\n----- pid %d at %04d-%02d-%02d %02d:%02d:%02d -----\n"
        // "Cmd line: %s\n"
        // "......"
        // "----- end %d -----\n"

        BufferedReader br = null;
        String line;
        Matcher matcher;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        boolean found = false;

        try {
            br = new BufferedReader(new FileReader(filepath));
            while ((line = br.readLine()) != null) {
                if (!found && line.startsWith("----- pid ")) {

                    //check current line for PID and log time
                    //按照anr文件的格式 尝试获取pid和发生时间
                    matcher = patPidTime.matcher(line);
                    if (!matcher.find() || matcher.groupCount() != 2) {
                        continue;
                    }
                    String sPid = matcher.group(1);
                    String sLogTime = matcher.group(2);
                    if (sPid == null || sLogTime == null) {
                        continue;
                    }
                    // 这里check一下pid anr监控在data/anr的时候 实际上所有的日志都会写到这里
                    // so...稳妥起见 应该check一下pid
                    if (pid != Integer.parseInt(sPid)) {
                        continue; //check PID
                    }
                    Date dLogTime = dateFormat.parse(sLogTime);
                    if (dLogTime == null) {
                        continue;
                    }
                    long logTime = dLogTime.getTime();
                    //这里check了一下是否是本次发生的anr文件
                    //因为在之前check了 anrtime - lasttime < anrTimeoutMs 满足直接停止了 说明可能是一次anr的多次记录
                    //这里再check一下 日志记录时间与anr发生时间 如果大于 anrTimeoutMs 说明时间点不match 可能是旧的anr记录 直接跳过
                    if (Math.abs(logTime - anrTime) > anrTimeoutMs) {
                        continue; //check log time
                    }

                    //check next line for process name
                    line = br.readLine();
                    if (line == null) {
                        break;
                    }
                    //获取进程名称
                    matcher = patProcessName.matcher(line);
                    if (!matcher.find() || matcher.groupCount() != 1) {
                        continue;
                    }
                    String pName = matcher.group(1);
                    //这里再check一下是否是当前的进程 用名字进行了check
                    if (pName == null || !(pName.equals(this.processName))) {
                        continue; //check process name
                    }
                    //当找到pid和pname后 我们就找到了目标文件
                    found = true;

                    sb.append(line).append('\n');
                    sb.append("Mode: Watching /data/anr/*\n");

                    continue;
                }

                if (found) {
                    if (line.startsWith("----- end ")) {
                        break;
                    } else {
                        //当找到anr文件后 直接将后面的内容追加到sb中即可
                        sb.append(line).append('\n');
                    }
                }
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
