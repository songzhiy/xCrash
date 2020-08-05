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

// Created by caikelun on 2019-05-20.
package xcrash;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 这里实际上构建了4种类型的文件：
 * 1、java crash log
 * 2、native crash log
 * 3、arn log
 * 4、placeholder log
 * 当我们的各类型存储的文件大于了设定的值时，需要对文件进行存储：
 * 这里的逻辑简单概括为：
 * 1、如果没有设置placeholder文件的数量，那么直接对多余的文件（对文件排序后，选择最早的文件）进行删除
 * 2、如果设置了placeholder文件数量，则先check 现有的clean文件数量是否已经大于了placeholder的max值
 *    如果大于，直接删除待删除文件
 * 3、如果当前placeholder clean文件数量小于placeholder的max值，那么直接将当前待删除文件标记为dirty
 *    同时对其向clean文件转换 完成placeholder的占位作用
 */
class FileManager {

    private String placeholderPrefix = "placeholder";
    private String placeholderCleanSuffix = ".clean.xcrash";
    private String placeholderDirtySuffix = ".dirty.xcrash";
    private String logDir = null;
    private int javaLogCountMax = 0;
    private int nativeLogCountMax = 0;
    private int anrLogCountMax = 0;
    private int traceLogCountMax = 1;
    private int placeholderCountMax = 0;
    private int placeholderSizeKb = 0;
    private int delayMs = 0;
    private AtomicInteger unique = new AtomicInteger();
    private static final FileManager instance = new FileManager();

    private FileManager() {
    }

    static FileManager getInstance() {
        return instance;
    }

    /**
     * 初始化时 主要进行了简单的参数赋值以及对现有文件的整理工作
     * @param logDir
     * @param javaLogCountMax
     * @param nativeLogCountMax
     * @param anrLogCountMax
     * @param placeholderCountMax
     * @param placeholderSizeKb
     * @param delayMs
     */
    void initialize(String logDir, int javaLogCountMax, int nativeLogCountMax, int anrLogCountMax, int placeholderCountMax, int placeholderSizeKb, int delayMs) {
        this.logDir = logDir;
        this.javaLogCountMax = javaLogCountMax;
        this.nativeLogCountMax = nativeLogCountMax;
        this.anrLogCountMax = anrLogCountMax;
        this.placeholderCountMax = placeholderCountMax;
        this.placeholderSizeKb = placeholderSizeKb;
        this.delayMs = delayMs;

        try {
            File dir = new File(logDir);
            if (!dir.exists() || !dir.isDirectory()) {
                return;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            //统计一波现有crash日志记录的文件夹各类型文件
            //便于进行空间整理与控制
            int javaLogCount = 0;
            int nativeLogCount = 0;
            int anrLogCount = 0;
            int traceLogCount = 0;
            int placeholderCleanCount = 0;
            int placeholderDirtyCount = 0;
            for (final File file : files) {
                if (file.isFile()) {
                    String name = file.getName();
                    if (name.startsWith(Util.logPrefix + "_")) {
                        if (name.endsWith(Util.javaLogSuffix)) {
                            javaLogCount++;
                        } else if (name.endsWith(Util.nativeLogSuffix)) {
                            nativeLogCount++;
                        } else if (name.endsWith(Util.anrLogSuffix)) {
                            anrLogCount++;
                        } else if (name.endsWith(Util.traceLogSuffix)) {
                            traceLogCount++;
                        }
                    } else if (name.startsWith(placeholderPrefix + "_")) {
                        if (name.endsWith(placeholderCleanSuffix)) {
                            placeholderCleanCount++;
                        } else if (name.endsWith(placeholderDirtySuffix)) {
                            placeholderDirtyCount++;
                        }
                    }
                }
            }

            if (javaLogCount <= this.javaLogCountMax
                && nativeLogCount <= this.nativeLogCountMax
                && anrLogCount <= this.anrLogCountMax
                && traceLogCount <= this.traceLogCountMax
                && placeholderCleanCount == this.placeholderCountMax
                && placeholderDirtyCount == 0) {
                //everything OK, need to do nothing
                this.delayMs = -1;
            } else if (javaLogCount > this.javaLogCountMax + 10
                || nativeLogCount > this.nativeLogCountMax + 10
                || anrLogCount > this.anrLogCountMax + 10
                || traceLogCount > this.traceLogCountMax + 10
                || placeholderCleanCount > this.placeholderCountMax + 10
                || placeholderDirtyCount > 10) {
                //too many unwanted files, clean up now
                doMaintain();
                this.delayMs = -1;
            } else if (javaLogCount > this.javaLogCountMax
                || nativeLogCount > this.nativeLogCountMax
                || anrLogCount > this.anrLogCountMax
                || traceLogCount > this.traceLogCountMax
                || placeholderCleanCount > this.placeholderCountMax
                || placeholderDirtyCount > 0) {
                //have some unwanted files, clean up as soon as possible
                this.delayMs = 0;
            }
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "FileManager init failed", e);
        }
    }

    void maintain() {
        if (this.logDir == null || this.delayMs < 0) {
            return;
        }

        try {
            String threadName = "xcrash_file_mgr";
            if (delayMs == 0) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        doMaintain();
                    }
                }, threadName).start();
            } else {
                new Timer(threadName).schedule(
                    new TimerTask() {
                        @Override
                        public void run() {
                            doMaintain();
                        }
                    }, delayMs
                );
            }
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "FileManager maintain start failed", e);
        }
    }

    boolean maintainAnr() {
        if (!Util.checkAndCreateDir(logDir)) {
            return false;
        }
        File dir = new File(logDir);

        try {
            return doMaintainTombstoneType(dir, Util.anrLogSuffix, anrLogCountMax);
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "FileManager maintainAnr failed", e);
            return false;
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    File createLogFile(String filePath) {
        if (this.logDir == null) {
            return null;
        }

        if (!Util.checkAndCreateDir(logDir)) {
            return null;
        }

        File newFile = new File(filePath);

        //clean placeholder files
        File dir = new File(logDir);
        File[] cleanFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(placeholderPrefix + "_") && name.endsWith(placeholderCleanSuffix);
            }
        });

        if (cleanFiles != null) {
            //try to rename from clean placeholder file
            int cleanFilesCount = cleanFiles.length;
            while (cleanFilesCount > 0) {
                File cleanFile = cleanFiles[cleanFilesCount - 1];
                try {
                    if (cleanFile.renameTo(newFile)) {
                        return newFile;
                    }
                } catch (Exception e) {
                    XCrash.getLogger().e(Util.TAG, "FileManager createLogFile by renameTo failed", e);
                }
                cleanFile.delete();
                cleanFilesCount--;
            }
        }

        //try to create new file
        try {
            if (newFile.createNewFile()) {
                return newFile;
            } else {
                XCrash.getLogger().e(Util.TAG, "FileManager createLogFile by createNewFile failed, file already exists");
                return null;
            }
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "FileManager createLogFile by createNewFile failed", e);
            return null;
        }
    }

    boolean appendText(String logPath, String text) {
        RandomAccessFile raf = null;

        try {
            raf = new RandomAccessFile(logPath, "rws");

            //get the write position
            long pos = 0;
            if (raf.length() > 0) {
                FileChannel fc = raf.getChannel();
                MappedByteBuffer mbb = fc.map(FileChannel.MapMode.READ_ONLY, 0, raf.length());
                for (pos = raf.length(); pos > 0; pos--) {
                    if (mbb.get((int) pos - 1) != (byte) 0) {
                        break;
                    }
                }
            }

            //write text
            raf.seek(pos);
            raf.write(text.getBytes("UTF-8"));

            return true;
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "FileManager appendText failed", e);
            return false;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 这里recycler的逻辑如下：
     * 1、首先check是否设置了允许placeholder文件缓存 即 this.placeholderCountMax <= 0
     *    如果不允许缓存，直接删除待删除文件
     * 2、寻找placeholder_xxxxx.clean.xrash文件 如果该文件数量已经超过 placeholder允许的最大数量 直接删除待删除文件
     * 3、将待删除文件改名为 placeholder_xxxx.dirty.crash文件
     *    如果改名失败，则尝试删除文件
     *    如果改名成功，则尝试清理这个dirty文件 实际上是将该文件内部所有数据写为0
     * @param logFile
     * @return
     */
    @SuppressWarnings({"unused"})
    boolean recycleLogFile(File logFile) {
        if (logFile == null) {
            return false;
        }

        if (this.logDir == null || this.placeholderCountMax <= 0) {
            try {
                return logFile.delete();
            } catch (Exception ignored) {
                return false;
            }
        }

        try {
            File dir = new File(logDir);
            File[] cleanFiles = dir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.startsWith(placeholderPrefix + "_") && name.endsWith(placeholderCleanSuffix);
                }
            });
            if (cleanFiles != null && cleanFiles.length >= this.placeholderCountMax) {
                try {
                    return logFile.delete();
                } catch (Exception ignored) {
                    return false;
                }
            }

            //rename to dirty file
            String dirtyFilePath = String.format(Locale.US, "%s/%s_%020d%s", logDir, placeholderPrefix, new Date().getTime() * 1000 + getNextUnique(), placeholderDirtySuffix);
            File dirtyFile = new File(dirtyFilePath);
            if (!logFile.renameTo(dirtyFile)) {
                try {
                    return logFile.delete();
                } catch (Exception ignored) {
                    return false;
                }
            }

            //clean the dirty file
            return cleanTheDirtyFile(dirtyFile);
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "FileManager recycleLogFile failed", e);
            try {
                return logFile.delete();
            } catch (Exception ignored) {
                return false;
            }
        }
    }


    private void doMaintain() {
        if (!Util.checkAndCreateDir(logDir)) {
            return;
        }
        File dir = new File(logDir);

        try {
            //尝试对crash日志空间进行清理
            doMaintainTombstone(dir);
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "FileManager doMaintainTombstone failed", e);
        }

        try {
            //整合placeholder相关的文件
            doMaintainPlaceholder(dir);
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "FileManager doMaintainPlaceholder failed", e);
        }
    }

    private void doMaintainTombstone(File dir) {
        //尝试对各种类型的文件进行回收操作
        doMaintainTombstoneType(dir, Util.nativeLogSuffix, nativeLogCountMax);
        doMaintainTombstoneType(dir, Util.javaLogSuffix, javaLogCountMax);
        doMaintainTombstoneType(dir, Util.anrLogSuffix, anrLogCountMax);
        doMaintainTombstoneType(dir, Util.traceLogSuffix, traceLogCountMax);
    }

    /**
     * 对文件进行回收操作：
     * 1、首先对文件类型进行筛选
     * 2、对筛选后的文件进行排序
     * 3、将最旧的文件进行回收操作
     * @param dir
     * @param logSuffix
     * @param logCountMax
     * @return
     */
    private boolean doMaintainTombstoneType(File dir, final String logSuffix, int logCountMax) {
        File[] files = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(Util.logPrefix + "_") && name.endsWith(logSuffix);
            }
        });

        boolean result = true;
        if (files != null && files.length > logCountMax) {
            if (logCountMax > 0) {
                Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        return f1.getName().compareTo(f2.getName());
                    }
                });
            }
            for (int i = 0; i < files.length - logCountMax; i++) {
                if (!recycleLogFile(files[i])) {
                    result = false;
                }
            }
        }
        return result;
    }

    /**
     * 对现有的placeholder_xxxx.clean.xcrash、placeholder_xxxx.dirty.xcrash文件进行整合
     * 1、check是否含有clean类型文件 如果没有 直接return？
     *   这里有个矛盾点，后面当clean文件较少时 还会通过创建文件 作为dirty文件来构建clean文件 但这里竟然直接return了
     * 2、check是否有dirty类型文件 如果没有 直接return？
     *   这里有个矛盾点，后面当clean文件较少时 还会通过创建文件 作为dirty文件来构建clean文件 但这里竟然直接return了
     * 3、当既存在clean文件 又存在dirty文件时 开始整理
     *    （1）、首先判断clean文件数量是否大于placeholder的允许总数 如果大于了 则进入4，进行最后扫尾工作
     *    （2）、当存在dirty文件时 尝试将dirty文件转换为clean文件
     *    （3）、当不存在dirty文件时 构建新文件作为dirty文件 转换为clean文件
     *     (4)、为了避免创建过多clean文件 还有个check条件 ++i > this.placeholderCountMax * 2 时 break
     *          因为有外层while循环兜底 没懂（4）这个条件啥时候能触发出来
     * 4、将clean文件的数量补充到placeholder允许的最大数值后 进行最后的扫尾工作
     *    （1）、将多余的clean文件删除掉
     *    （2）、将多余的dirty文件删除掉
     * @param dir
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void doMaintainPlaceholder(File dir) {
        //get all existing placeholder files
        File[] cleanFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(placeholderPrefix + "_") && name.endsWith(placeholderCleanSuffix);
            }
        });
        if (cleanFiles == null) {
            return;
        }
        File[] dirtyFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(placeholderPrefix + "_") && name.endsWith(placeholderDirtySuffix);
            }
        });
        if (dirtyFiles == null) {
            return;
        }

        //create clean placeholder files from dirty placeholder files or new files
        int i = 0;
        int cleanFilesCount = cleanFiles.length;
        int dirtyFilesCount = dirtyFiles.length;
        while (cleanFilesCount < this.placeholderCountMax) {
            if (dirtyFilesCount > 0) {
                File dirtyFile = dirtyFiles[dirtyFilesCount - 1];
                if (cleanTheDirtyFile(dirtyFile)) {
                    cleanFilesCount++;
                }
                dirtyFilesCount--;
            } else {
                try {
                    File dirtyFile = new File(String.format(Locale.US, "%s/%s_%020d%s", logDir, placeholderPrefix, new Date().getTime() * 1000 + getNextUnique(), placeholderDirtySuffix));
                    if (dirtyFile.createNewFile()) {
                        if (cleanTheDirtyFile(dirtyFile)) {
                            cleanFilesCount++;
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            //don't try it too many times
            //没懂这个条件啥时候会触发出来
            if (++i > this.placeholderCountMax * 2) {
                break;
            }
        }

        //reload clean placeholder files list and dirty placeholder files list if needed
        if (i > 0) {
            cleanFiles = dir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.startsWith(placeholderPrefix + "_") && name.endsWith(placeholderCleanSuffix);
                }
            });
            dirtyFiles = dir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.startsWith(placeholderPrefix + "_") && name.endsWith(placeholderDirtySuffix);
                }
            });
        }

        //don't keep too many clean placeholder files
        if (cleanFiles != null && cleanFiles.length > this.placeholderCountMax) {
            for (i = 0; i < cleanFiles.length - this.placeholderCountMax; i++) {
                cleanFiles[i].delete();
            }
        }

        //delete all remaining dirty placeholder files
        if (dirtyFiles != null) {
            for (File dirtyFile : dirtyFiles) {
                dirtyFile.delete();
            }
        }
    }

    /**
     * 尝试清理被标记为dirty的文件：核心思想，将dirty文件的所有内容 全部写为0
     * 如果尝试该错做失败，则尝试删除待删除文件
     * @param dirtyFile
     * @return
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private boolean cleanTheDirtyFile(File dirtyFile) {

        FileOutputStream stream = null;
        boolean succeeded = false;

        try {
            byte[] block = new byte[1024];
            Arrays.fill(block, (byte) 0);

            //默认block块为placeholder的最大kb数 默认为128 由xcrash初始化时设置 用户可修改
            long blockCount = placeholderSizeKb;
            //获取待删除文件的大小
            long dirtyFileSize = dirtyFile.length();
            //当待删除文件大小大于默认数值时，需更新block大小 更新为待删除的文件大小/1024（+1）
            //这里+1的操作是当非填满时的最后的剩余字节数 需要再占用一个
            if (dirtyFileSize > placeholderSizeKb * 1024) {
                blockCount = dirtyFileSize / 1024;
                if (dirtyFileSize % 1024 != 0) {
                    blockCount++;
                }
            }

            //clean the dirty file
            stream = new FileOutputStream(dirtyFile.getAbsoluteFile(), false);
            //开始对待删除的文件进行写入工作 直接将block写入 block为全0数据
            for (int i = 0; i < blockCount; i++) {
                if (i + 1 == blockCount && dirtyFileSize % 1024 != 0) {
                    //the last block 当写到最后一个区块时 需要注意字节数
                    stream.write(block, 0, (int) (dirtyFileSize % 1024));
                } else {
                    //非最后一个区块 直接写入即可
                    stream.write(block);
                }
            }
            stream.flush();

            //rename the dirty file to clean file
            //当将待删除文件的数据全部写为0后
            //将其名称由placeholder_xxx.dirty.xcrash 变更为 placeholder_xxxx.clean.xcrash
            String newCleanFilePath = String.format(Locale.US, "%s/%s_%020d%s", logDir, placeholderPrefix, new Date().getTime() * 1000 + getNextUnique(), placeholderCleanSuffix);
            succeeded = dirtyFile.renameTo(new File(newCleanFilePath));
        } catch (Exception e) {
            XCrash.getLogger().e(Util.TAG, "FileManager cleanTheDirtyFile failed", e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
            }
        }

        if (!succeeded) {
            try {
                dirtyFile.delete();
            } catch (Exception ignored) {
            }
        }

        return succeeded;
    }

    private int getNextUnique() {
        int i = unique.incrementAndGet();
        if (i >= 999) {
            unique.set(0);
        }
        return i;
    }
}
