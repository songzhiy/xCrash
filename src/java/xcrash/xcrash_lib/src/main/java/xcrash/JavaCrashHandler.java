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

// Created by caikelun on 2019-03-07.
package xcrash;

import android.annotation.SuppressLint;
import android.os.Process;
import android.text.TextUtils;

import java.io.File;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@SuppressLint("StaticFieldLeak")
/**
 * 实际上java层仍然是通过 UncaughtExceptionHandler进行的异常监听
 * Note:Thread.setUncaughtExceptionHandler设置的是一个静态对象 当所有的线程发生崩溃时 都会进行捕获
 *
 * JavaCrashHandler 可以捕获所有线程的java层崩溃异常 主线程 + 其他线程
 *
 * 当跨进程时，由于在application会重新被执行生命周期，因此只要Thread.setUncaughtExceptionHandler，那么也就可以继续捕捉新进程异常
 *
 * 该类在捕获主线程异常后，主要进行了如下操作：
 *
 * 1、生成异常头部信息（主要为设备相关信息）+ 崩溃栈信息
 * 2、捕获崩溃时logcat信息
 * 3、捕获所有打开的文件温习
 * 4、捕获网路信息
 * 5、捕获内存信息
 * 6、记录app崩溃时前台 or 后台
 * 7、记录其他线程信息
 * 8、回调上层ICrashCallback发生了崩溃
 */
class JavaCrashHandler implements UncaughtExceptionHandler {

    private static final JavaCrashHandler instance = new JavaCrashHandler();

    private final Date startTime = new Date();

    private int pid;
    private String processName;
    private String appId;
    private String appVersion;
    private boolean rethrow;
    private String logDir;
    private int logcatSystemLines;
    private int logcatEventsLines;
    private int logcatMainLines;
    private boolean dumpFds;
    private boolean dumpNetworkInfo;
    private boolean dumpAllThreads;
    private int dumpAllThreadsCountMax;
    private String[] dumpAllThreadsWhiteList;
    private ICrashCallback callback;
    private UncaughtExceptionHandler defaultHandler = null;

    private JavaCrashHandler() {
    }

    static JavaCrashHandler getInstance() {
        return instance;
    }

    void initialize(int pid, String processName, String appId, String appVersion, String logDir, boolean rethrow,
                    int logcatSystemLines, int logcatEventsLines, int logcatMainLines,
                    boolean dumpFds, boolean dumpNetworkInfo, boolean dumpAllThreads, int dumpAllThreadsCountMax, String[] dumpAllThreadsWhiteList,
                    ICrashCallback callback) {
        this.pid = pid;
        this.processName = (TextUtils.isEmpty(processName) ? "unknown" : processName);
        this.appId = appId;
        this.appVersion = appVersion;
        this.rethrow = rethrow;
        this.logDir = logDir;
        this.logcatSystemLines = logcatSystemLines;
        this.logcatEventsLines = logcatEventsLines;
        this.logcatMainLines = logcatMainLines;
        this.dumpFds = dumpFds;
        this.dumpNetworkInfo = dumpNetworkInfo;
        this.dumpAllThreads = dumpAllThreads;
        this.dumpAllThreadsCountMax = dumpAllThreadsCountMax;
        this.dumpAllThreadsWhiteList = dumpAllThreadsWhiteList;
        this.callback = callback;
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        try {
            Thread.setDefaultUncaughtExceptionHandler(this);
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "JavaCrashHandler setDefaultUncaughtExceptionHandler failed", e);
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        // TODO: 2020/8/5 这里为什么又设置回去了？
        if (defaultHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(defaultHandler);
        }

        try {
            //处理下异常
            handleException(thread, throwable);
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "JavaCrashHandler handleException failed", e);
        }

        //判断下是否要再次抛出
        if (this.rethrow) {
            //再次抛出
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        } else {
            //直接关闭掉所有页面 杀掉当前进程
            ActivityMonitor.getInstance().finishAllActivities();
            Process.killProcess(this.pid);
            System.exit(10);
        }
    }

    /**
     * 1、先暂停掉native、anr的监听
     * 2、构建一个崩溃日志: 优先采用placeholder clean文件，如果复用失败，则重新构建一个新文件
     * 3、生成崩溃栈头部信息（主要为设备相关信息） + 崩溃栈信息
     * 4、通过/system/bin/logcat来进行日志捕获工作
     * 5、通过 /proc/self/fd 文件描述符来获取当前开启的所有文件
     * 6、捕获当前的网络情况
     * 7、获取内存相关信息：主要包含
     *    内存信息 From: /proc/meminfo
     *    进程信息 From: /proc/PID/status
     *    进程限制 From: /proc/PID/limits
     * 8、记录下崩溃时 app 是前台还是后台
     * 9、记录下其他线程的情况
     * 10、回调ICrashCallback接口 通知上层发生了崩溃异常
     * @param thread
     * @param throwable
     */
    private void handleException(Thread thread, Throwable throwable) {
        Date crashTime = new Date();

        //notify the java crash
        //当java主线程发生崩溃时，需要通知native和anr停止监控
        //note：anr是采用文件监听的方式进行 必须停止掉
        NativeHandler.getInstance().notifyJavaCrashed();
        AnrHandler.getInstance().notifyJavaCrashed();

        //create log file
        //构建一个崩溃日志文件
        File logFile = null;
        try {
            String logPath = String.format(Locale.US, "%s/%s_%020d_%s__%s%s", logDir, Util.logPrefix, startTime.getTime() * 1000, appVersion, processName, Util.javaLogSuffix);
            //这里优先尝试复用placeholder clean文件 如果不行 再重新创建一个文件
            logFile = FileManager.getInstance().createLogFile(logPath);
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "JavaCrashHandler createLogFile failed", e);
        }

        //get emergency
        String emergency = null;
        try {
            //获取崩溃栈信息：
            //这部分主要包含两部分内容：
            //1、崩溃信息头部 含有一系列设备内容
            //2、崩溃栈
            emergency = getEmergency(crashTime, thread, throwable);
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "JavaCrashHandler getEmergency failed", e);
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
                //通过 /system/bin/logcat 来进行日志的捕获工作
                if (logcatMainLines > 0 || logcatSystemLines > 0 || logcatEventsLines > 0) {
                    raf.write(Util.getLogcat(logcatMainLines, logcatSystemLines, logcatEventsLines).getBytes("UTF-8"));
                }

                //write fds
                //通过 /proc/self/fd 文件描述符来获取当前开启的所有文件
                if (dumpFds) {
                    raf.write(Util.getFds().getBytes("UTF-8"));
                }

                //write network info
                //捕获当前的网络情况
                if (dumpNetworkInfo) {
                    raf.write(Util.getNetworkInfo().getBytes("UTF-8"));
                }

                //write memory info
                //获取内存相关信息：主要包含
                //1、内存信息
                //2、进程信息
                //3、进程限制
                raf.write(Util.getMemoryInfo().getBytes("UTF-8"));

                //write background / foreground
                //记录下崩溃时 app 是前台还是后台
                raf.write(("foreground:\n" + (ActivityMonitor.getInstance().isApplicationForeground() ? "yes" : "no") + "\n\n").getBytes("UTF-8"));

                //write other threads info
                //记录下其他线程的情况
                if (dumpAllThreads) {
                    raf.write(getOtherThreadsInfo(thread).getBytes("UTF-8"));
                }
            } catch (Exception e) {
                XCrash.getLogger().e(Util.TAG, "JavaCrashHandler write log file failed", e);
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
        //回调上层 发生了异常情况
        if (callback != null) {
            try {
                callback.onCrash(logFile == null ? null : logFile.getAbsolutePath(), emergency);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 包含两部分内容：
     * 1、崩溃栈的头部信息，主要为设备相关信息
     * 2、崩溃栈
     * @param crashTime
     * @param thread
     * @param throwable
     * @return
     */
    private String getEmergency(Date crashTime, Thread thread, Throwable throwable) {

        //stack stace
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stacktrace = sw.toString();

        return Util.getLogHeader(startTime, crashTime, Util.javaCrashType, appId, appVersion)
                + "pid: " + pid + ", tid: " + Process.myTid() + ", name: " + thread.getName() + "  >>> " + processName + " <<<\n"
                + "\n"
                + "java stacktrace:\n"
                + stacktrace
                + "\n";
    }

    /**
     * 通过Thread.getAllStacktraces()来捕获所有的线程信息 并遍历
     * 1、先判断是否和当前线程是一个 是 continue
     * 2、判断是否在线程关注白名单中 否 continue
     * 3、记录线程的相关栈信息
     * 4、整合所有其他线程的统计信息 如 线程总数、符合白名单的线程、dump下信息的线程数量
     * * @param crashedThread
     * @return
     */
    private String getOtherThreadsInfo(Thread crashedThread) {

        int thdMatchedRegex = 0;
        int thdIgnoredByLimit = 0;
        int thdDumped = 0;

        //build whitelist regex list
        //构建一个 线程名称正则 的数组
        ArrayList<Pattern> whiteList = null;
        if (dumpAllThreadsWhiteList != null) {
            whiteList = new ArrayList<Pattern>();
            for (String s : dumpAllThreadsWhiteList) {
                try {
                    whiteList.add(Pattern.compile(s));
                } catch (Exception e) {
                    XCrash.getLogger().w(Util.TAG, "JavaCrashHandler pattern compile failed", e);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        //通过Thread.getAllStackTraces()获取到所有线程的栈信息
        Map<Thread, StackTraceElement[]> map = Thread.getAllStackTraces();
        //遍历所有的线程栈
        for (Map.Entry<Thread, StackTraceElement[]> entry : map.entrySet()) {

            Thread thd = entry.getKey();
            StackTraceElement[] stacktrace = entry.getValue();

            //skip the crashed thread
            //线程是当前崩溃线程时 直接continue
            if (thd.getName().equals(crashedThread.getName())) continue;

            //check regex for thread name
            //线程与关注线程不符合时 直接continue
            if (whiteList != null && !matchThreadName(whiteList, thd.getName())) continue;
            //记录下match的线程数量
            thdMatchedRegex++;

            //check dump count limit
            //如果记录的线程数量已经大于了允许记录的最大值 则记录忽略数量
            if (dumpAllThreadsCountMax > 0 && thdDumped >= dumpAllThreadsCountMax) {
                thdIgnoredByLimit++;
                continue;
            }

            sb.append(Util.sepOtherThreads + "\n");
            sb.append("pid: ").append(pid).append(", tid: ").append(thd.getId()).append(", name: ").append(thd.getName()).append("  >>> ").append(processName).append(" <<<\n");
            sb.append("\n");
            sb.append("java stacktrace:\n");
            //记录每个stack trace的相关日志
            for (StackTraceElement element : stacktrace) {
                sb.append("    at ").append(element.toString()).append("\n");
            }
            sb.append("\n");
            //记录下dump下来的线程数量
            thdDumped++;
        }

        //记录一下其他线程的整体信息
        if (map.size() > 1) {
            if (thdDumped == 0) {
                sb.append(Util.sepOtherThreads + "\n");
            }

            sb.append("total JVM threads (exclude the crashed thread): ").append(map.size() - 1).append("\n");
            if (whiteList != null) {
                sb.append("JVM threads matched whitelist: ").append(thdMatchedRegex).append("\n");
            }
            if (dumpAllThreadsCountMax > 0) {
                sb.append("JVM threads ignored by max count limit: ").append(thdIgnoredByLimit).append("\n");
            }
            sb.append("dumped JVM threads:").append(thdDumped).append("\n");
            sb.append(Util.sepOtherThreadsEnding + "\n");
        }

        return sb.toString();
    }

    private boolean matchThreadName(ArrayList<Pattern> whiteList, String threadName) {
        for (Pattern pat : whiteList) {
            if (pat.matcher(threadName).matches()) {
                return true;
            }
        }
        return false;
    }
}
