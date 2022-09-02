/*
 * Copyright (C) 2016 MarkZhai (http://zhaiyifan.cn).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.moduth.blockcanary;

import android.os.Build;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.github.moduth.blockcanary.internal.BlockInfo;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dumps cpu usage.
 */
class CpuSampler extends AbstractSampler {

    private static final String TAG = "CpuSampler";
    private static final int BUFFER_SIZE = 1000;
    private static final int TYPE_CPU_ALL = 1;
    private static final int TYPE_CPU_SELF_APP = 2;
    /**
     * TODO: Explain how we define cpu busy in README
     */
    private final int BUSY_TIME;
    private static final int MAX_ENTRY_COUNT = BlockCanaryInternals.getContext().reportRecentOneMessage() ? 1 : 10;

    private final LinkedHashMap<Long, String> mCpuInfoEntries = new LinkedHashMap<>();
    private int mPid = 0;
    private long mUserLast = 0;
    private long mSystemLast = 0;
    private long mIdleLast = 0;
    private long mIoWaitLast = 0;
    private long mTotalLast = 0;
    private long mAppCpuTimeLast = 0;
    private boolean mIsOverAndroidO;

    public CpuSampler(long sampleInterval) {
        super(sampleInterval);
        BUSY_TIME = (int) (mSampleInterval * 1.2f);
        Log.d(TAG, Build.VERSION_CODES.O + ",current is " + Build.VERSION.SDK_INT);
        mIsOverAndroidO = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    @Override
    public void start() {
        super.start();
        reset();
    }

    /**
     * Get cpu rate information
     *
     * @return string show cpu rate information
     */
    public String getCpuRateInfo() {
        StringBuilder sb = new StringBuilder();
        synchronized (mCpuInfoEntries) {
            Log.e(TAG, "size is " + mCpuInfoEntries.size());
            for (Map.Entry<Long, String> entry : mCpuInfoEntries.entrySet()) {
                long time = entry.getKey();
                sb.append(BlockInfo.TIME_FORMATTER.format(time))
                        .append(' ')
                        .append(entry.getValue())
                        .append(BlockInfo.SEPARATOR);
            }
        }
        Log.e(TAG, "result is " + sb);
        return sb.toString();
    }

    public boolean isCpuBusy(long start, long end) {
        if (end - start > mSampleInterval) {
            long s = start - mSampleInterval;
            long e = start + mSampleInterval;
            long last = 0;
            synchronized (mCpuInfoEntries) {
                for (Map.Entry<Long, String> entry : mCpuInfoEntries.entrySet()) {
                    long time = entry.getKey();
                    if (s < time && time < e) {
                        if (last != 0 && time - last > BUSY_TIME) {
                            return true;
                        }
                        last = time;
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected void doSample() {
        if (mIsOverAndroidO) {
            // getCPUDataFromOverAndroidO();
            // testCpu();
            inputTopCommandInfo();
            // getCPUDataFromAndroidO();
        } else {
            getCPUData();
        }
    }

    private void inputTopCommandInfo() {
        Log.e(TAG, "start input ");
        java.lang.Process process = null;
        BufferedReader reader = null;
        BufferedWriter writer = null;
        try {
            process = Runtime.getRuntime().exec("top -n 1");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));//, Charset.forName("GBK")
            File file = BlockCanaryContext.provideContext().getFilesDir();
            if (file.exists()) {
                File cpuInfoFile = new File(file.getAbsolutePath(), "1.txt");
                if (!cpuInfoFile.exists()) {
                    cpuInfoFile.createNewFile();
                }
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(cpuInfoFile)));
                String cpuInfoLine;
                while ((cpuInfoLine = reader.readLine()) != null) {
                    writer.write(cpuInfoLine + "\n\t");
                    Log.e(TAG, "every cpuInfoLine:" + cpuInfoLine);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (process != null) {
                process.destroy();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void testCpu() {
        BufferedInputStream reader = null;
        java.lang.Process process = null;
        try {
            if (mPid == 0) {
                mPid = android.os.Process.myPid();
            }
            // process = Runtime.getRuntime().exec(String.format("cat /proc/%d/stat", mPid));
            process = Runtime.getRuntime().exec("cat /proc/stat");
            // reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            reader = new BufferedInputStream(process.getInputStream());
            if (null != reader) {
                Log.e(TAG, "reader is not null" + reader.read() + ",");
                int flag = 0;
                byte[] buffer = new byte[1024];
                while ((flag = reader.read(buffer)) != -1) {
                    Log.e(TAG, new String(buffer, 0, flag));
                }
            } else {
                Log.e(TAG, "reader is null");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (process != null) {
                process.destroy();
            }
        }
    }

    private void getCPUDataFromOverAndroidO() {
        parse(getCpuInfoFromProcessByType(TYPE_CPU_ALL), getCpuInfoFromProcessByType(TYPE_CPU_SELF_APP));
    }

    /**
     * @param type one of {@link #TYPE_CPU_ALL} ,{@link #TYPE_CPU_SELF_APP}
     * @return cpuInfo
     */
    private String getCpuInfoFromProcessByType(int type) {
        String command;
        String cpuInfo = null;
        if (type == TYPE_CPU_SELF_APP) {
            if (mPid == 0) {
                mPid = android.os.Process.myPid();
            }
            command = String.format("cat /proc/%d/stat", mPid);
        } else {
            command = "cat /proc/stat";
        }
        BufferedReader cpuReader = null;
        java.lang.Process cpuProcess = null;
        try {
            Log.e(TAG, "command is " + command);
            cpuProcess = Runtime.getRuntime().exec(command);
            cpuReader = new BufferedReader(new InputStreamReader(cpuProcess.getInputStream()));
            String line;
            while ((line = cpuReader.readLine()) != null) {
                Log.e(TAG, "line is " + line);
                cpuInfo = line;
            }
            if (cpuInfo == null) {
                cpuInfo = "";
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cpuReader != null) {
                try {
                    cpuReader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (cpuProcess != null) {
                cpuProcess.destroy();
            }
        }
        Log.d(TAG + mPid, cpuInfo);
        return cpuInfo;
    }

    private void getCPUData() {
        BufferedReader cpuReader = null;
        BufferedReader pidReader = null;
        try {
            cpuReader = new BufferedReader(new InputStreamReader(
                    new FileInputStream("/proc/stat")), BUFFER_SIZE);
            String cpuRate = cpuReader.readLine();
            if (cpuRate == null) {
                cpuRate = "";
            }

            if (mPid == 0) {
                mPid = android.os.Process.myPid();
            }
            pidReader = new BufferedReader(new InputStreamReader(
                    new FileInputStream("/proc/" + mPid + "/stat")), BUFFER_SIZE);
            String pidCpuRate = pidReader.readLine();
            if (pidCpuRate == null) {
                pidCpuRate = "";
            }
            parse(cpuRate, pidCpuRate);
        } catch (Throwable throwable) {
            Log.e(TAG, "doSample: ", throwable);
        } finally {
            try {
                if (cpuReader != null) {
                    cpuReader.close();
                }
                if (pidReader != null) {
                    pidReader.close();
                }
            } catch (IOException exception) {
                Log.e(TAG, "doSample: ", exception);
            }
        }
    }

    private void reset() {
        mUserLast = 0;
        mSystemLast = 0;
        mIdleLast = 0;
        mIoWaitLast = 0;
        mTotalLast = 0;
        mAppCpuTimeLast = 0;
    }

    private void parse(String cpuRate, String pidCpuRate) {
        String[] cpuInfoArray = cpuRate.split(" ");
        Log.e(TAG, "cpuRate size is " + cpuInfoArray.length);
        for (String s : cpuInfoArray) {
            Log.e(TAG, s);
        }
        if (cpuInfoArray.length < 9) {
            return;
        }
        long user = Long.parseLong(cpuInfoArray[2]);
        long nice = Long.parseLong(cpuInfoArray[3]);
        long system = Long.parseLong(cpuInfoArray[4]);
        long idle = Long.parseLong(cpuInfoArray[5]);
        long ioWait = Long.parseLong(cpuInfoArray[6]);
        long total = user + nice + system + idle + ioWait
                + Long.parseLong(cpuInfoArray[7])
                + Long.parseLong(cpuInfoArray[8]);

        String[] pidCpuInfoList = pidCpuRate.split(" ");
        Log.e(TAG, "pidCpuInfoList size is " + pidCpuInfoList.length);
        if (pidCpuInfoList.length < 17) {
            return;
        }

        long appCpuTime = Long.parseLong(pidCpuInfoList[13])
                + Long.parseLong(pidCpuInfoList[14])
                + Long.parseLong(pidCpuInfoList[15])
                + Long.parseLong(pidCpuInfoList[16]);

        if (mTotalLast != 0) {
            StringBuilder stringBuilder = new StringBuilder();
            long idleTime = idle - mIdleLast;
            long totalTime = total - mTotalLast;

            stringBuilder
                    //.append("cpu: ")
                    .append((totalTime - idleTime) * 100L / totalTime)
                    .append("% ")
                    //.append("app: ")
                    .append((appCpuTime - mAppCpuTimeLast) * 100L / totalTime)
                    .append("% ")
                    //.append("user:")
                    .append((user - mUserLast) * 100L / totalTime)
                    .append("% ")
                    //.append("system:")
                    .append((system - mSystemLast) * 100L / totalTime)
                    .append("% ")
                    //.append("ioWait:")
                    .append((ioWait - mIoWaitLast) * 100L / totalTime)
                    .append("% ");

            synchronized (mCpuInfoEntries) {
                mCpuInfoEntries.put(System.currentTimeMillis(), stringBuilder.toString());
                Log.e(TAG, "mCpuInfoEntries size is " + mCpuInfoEntries.size());
                if (mCpuInfoEntries.size() > MAX_ENTRY_COUNT) {
                    for (Map.Entry<Long, String> entry : mCpuInfoEntries.entrySet()) {
                        Long key = entry.getKey();
                        mCpuInfoEntries.remove(key);
                        break;
                    }
                }
            }
        }
        mUserLast = user;
        mSystemLast = system;
        mIdleLast = idle;
        mIoWaitLast = ioWait;
        mTotalLast = total;

        mAppCpuTimeLast = appCpuTime;
    }

    private void getCPUDataFromAndroidO() {
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec("top -n 1");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.forName("GBK")));
            String cpuInfoLine;
            int cpuIndex = -1;
            while ((cpuInfoLine = reader.readLine()) != null) {
                Log.e(TAG, "every cpuInfoLine:" + cpuInfoLine);
                cpuInfoLine = cpuInfoLine.trim();
                if (TextUtils.isEmpty(cpuInfoLine)) {
                    continue;
                }
                int tempIndex = getCpuColumnFromCpuInfoLine(cpuInfoLine);
                if (tempIndex != -1) {
                    cpuIndex = tempIndex;
                    continue;
                }
                Log.e(TAG, "cpuInfoLine:" + cpuInfoLine + ",index is" + cpuIndex);
                int startIndex = 0;
                if (-1 != cpuIndex && cpuInfoLine.contains(String.valueOf(Process.myPid()))) {
                    Log.e(TAG, "cpuInfoLine:" + cpuInfoLine + ",index is" + cpuIndex);
                    String[] values = cpuInfoLine.split("\\s+");
                    for (String element : values) {
                        Log.e(TAG, "==" + element);
                        if (element.contains(Process.myPid() + "")) {
                            break;
                        } else {
                            startIndex++;
                        }
                    }
                    String cpuValue = values[cpuIndex];
                    if (cpuValue.endsWith("%")) {
                        cpuValue = cpuValue.substring(0, cpuValue.lastIndexOf("%"));
                    }
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append(cpuValue).append("% ");
                    synchronized (mCpuInfoEntries) {
                        mCpuInfoEntries.put(System.currentTimeMillis(), stringBuilder.toString());
                        if (mCpuInfoEntries.size() > MAX_ENTRY_COUNT) {
                            for (Map.Entry<Long, String> entry : mCpuInfoEntries.entrySet()) {
                                Long key = entry.getKey();
                                mCpuInfoEntries.remove(key);
                                break;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }


    private int getCpuColumnFromCpuInfoLine(String cpuInfoLine) {
        if (cpuInfoLine.contains("CPU")) {
            Log.e(TAG, cpuInfoLine);
            String[] columns = cpuInfoLine.split("\\s+");
            int startIndex = 0;
            boolean isFindStartIndex = false;
            for (int i = 0; i < columns.length; i++) {
                if (!isFindStartIndex && !columns[i].contains("PID")) {
                    startIndex++;
                    continue;
                }
                if (!isFindStartIndex) {
                    isFindStartIndex = true;
                }
                if (columns[i].contains("CPU")) {
                    return i - startIndex;
                }
            }
        }
        return -1;
    }
}