package com.android.commands.monkey.ape;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalServerSocket;
import java.net.SocketOptions;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.Runnable;
import java.lang.RuntimeException;
import java.util.ArrayList;
import java.util.List;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.MonkeySourceApe;

/**
 * Communicate with MiniTrace
 */
public class MonkeyServer implements Runnable {

    class TargetMethod {
        public String clsname;
        public String mtdname;
        public String signature;
        public int mtdFlag;
        public int size;
        public byte buffer[];

        public TargetMethod (String clsname, String mtdname, String signature, int mtdFlag) throws IOException {
            this.clsname = clsname;
            this.mtdname = mtdname;
            this.signature = signature;
            this.mtdFlag = mtdFlag;
            if ((mtdFlag & (~(kMtdFlagEntered | kMtdFlagExited | kMtdFlagUnroll))) != 0)
                throw new RuntimeException("flag should be masked with 7");

            // fill buffer
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MonkeyServer.writeInt32(out, clsname.length());
            out.write(clsname.getBytes("UTF-8"));
            MonkeyServer.writeInt32(out, mtdname.length());
            out.write(mtdname.getBytes("UTF-8"));
            MonkeyServer.writeInt32(out, signature.length());
            out.write(signature.getBytes("UTF-8"));
            MonkeyServer.writeInt32(out, mtdFlag);
            buffer = out.toByteArray();
        }

        public int getSize() {
            return size;
        }

        public void writeTo(OutputStream os) throws IOException {
            os.write(buffer);
        }
    }

    private static final String SOCK_ADDRESS = "/dev/mt/ape";
    private static MonkeyServer instance = null;

    private static final int kHandShake         = 0x0abeabe0;
    private static final int kTargetEntered     = 0xabeabe01; // 4byte
    private static final int kTargetExited      = 0xabeabe02; // 4byte
    private static final int kTargetUnwind      = 0xabeabe03; // 4byte
    private static final int kIdle              = 0xabe0de04; // 8byte

    private static final int kMtdFlagEntered    = 0x00000001;
    private static final int kMtdFlagExited     = 0x00000002;
    private static final int kMtdFlagUnroll     = 0x00000004;

    private MonkeySourceApe ape;
    private PrintWriter serverlog_pw;

    // connection-specific values
    private LocalSocket socket;
    private InputStream is;
    private OutputStream os;

    // buffer to read 8~12 bytes
    private byte[] buffer;

    private byte[] headerbuffer;

    // Store method targets
    private List<TargetMethod> target_methods;
    private boolean target_taken;

    // Store time for last idle time
    private long last_idle_time; // must be protected with lock

    // Store time for last targeting method met
    private long last_target_time; // must be protected with lock
    private int last_method_id; // must be protected with lock

    private volatile int connection_cnt;
    private int mainTid;

    private boolean is_running;

    private Thread thread;

    private List<String> moved_directories;

    // custom local socket
    private LocalServerSocket lss;
    private Object impl;
    private Class<?> impl_class;
    private boolean mainThreadOnly;

    private MonkeyServer(boolean mainThreadOnly) throws IOException {
        try {
            impl_class = Class.forName("android.net.LocalSocketImpl");
            lss = new LocalServerSocket(SOCK_ADDRESS);

            Field localServerSocket_impl = lss.getClass().getDeclaredField("impl");
            localServerSocket_impl.setAccessible(true);
            impl = localServerSocket_impl.get(lss);

            // Class[] consParamTypes = {String.class};
            Method impl_setOption = impl_class.getMethod("setOption", int.class, Object.class);
            impl_setOption.invoke(impl, SocketOptions.SO_TIMEOUT, 1000);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException | NoSuchFieldException e) {
            e.printStackTrace();
            throw new RuntimeException("LocalSocketImpl");
        }

        last_idle_time = 0;
        last_target_time = 0;
        connection_cnt = 0;
        last_method_id = -1;
        mainTid = -1;
        is_running = true;
        buffer = new byte[128];
        parseTargetMtds();
        thread = new Thread(this);
        thread.setDaemon(true);
        moved_directories = new ArrayList<>();
        this.mainThreadOnly = mainThreadOnly;
        System.out.println("[APE_MT] MonkeyServer Initialized");
    }

    public Thread getThread() {
        return thread;
    }

    public void registerAPE(MonkeySourceApe ape) throws IOException {
        // log file
        File dir = ape.getOutputDirectory();
        if (!dir.exists()) { dir.mkdirs(); }
        serverlog_pw = new PrintWriter(new File(dir, "monkeyserver.log"));
        serverlog_pw.println("MonkeyServer log");
    }

    public static void makeInstance(boolean mainThreadOnly) throws IOException {
        instance = new MonkeyServer(mainThreadOnly);
    }

    public static MonkeyServer getInstance() {
        return instance;
    }

    public void parseTargetMtds() throws IOException {
        target_methods = new ArrayList<TargetMethod>();

        String targetmtdfile = Config.get("ape.mt.targetmtdfile");
        if (targetmtdfile == null)
            return;

        BufferedReader br = new BufferedReader(new FileReader(targetmtdfile));
        String line;
        while ((line = br.readLine()) != null) {
            // classname, mtdname, signature, flag
            if (line.equals(""))
                continue;
            if (line.startsWith("//"))
                continue;
            String[] tokens = line.split("\t");
            if (tokens.length != 4)
                throw new RuntimeException("Failed to parse line " + line + tokens.length);
            try {
                target_methods.add(new TargetMethod(tokens[0], tokens[1], tokens[2], Integer.parseInt(tokens[3])));
            } catch (IOException e) {
                throw new RuntimeException("Parsing targeting methods " + e.getMessage());
            }
        }
        System.out.println("[MonkeyServer] Total " + target_methods.size() + " methods are targeted");
    }

    public void close() {
        System.out.println("[MonkeyServer] Closing...");
        // close run
        is_running = false;
        try {
            is.close();
            os.close();
            lss.close();
        } catch (IOException e) {
            System.out.println("[MonkeyServer] impl.close() " + e.getMessage());
        }
        System.out.println("[MonkeyServer] server socket closed");

        // NOTICE: thread.join does not interrupt blocking on accept
        // try {
        //     thread.join();
        // } catch (InterruptedException e) {
        //     System.out.println("[MonkeyServer] Interrupted during thread.join");
        // }

        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
        System.out.println("[MonkeyServer] Thread terminated");

        // move mt data
        synchronized(this) {
            for (String directory: moved_directories) {
                System.out.println("[APE_MT] mt data " + directory);
            }
            moved_directories.clear();
        }

        // close file
        serverlog_pw.close();
        System.out.println("[MonkeyServer] Log closed");
    }

    public void alertCrash() {
        synchronized (this) {
            last_idle_time = -1; // crashed
            connection_cnt = 0;
            mainTid = -1;
            notifyAll();
        }
    }

    /**
     * return -1 for timeout otherwise last idle time
     */
    public long waitForIdle(long fromMillis, long timeoutMillis) {
        long time_end = System.currentTimeMillis() + timeoutMillis;
        long last_time_fetched;
        long time_current;
        while (true) {
            synchronized (this) {
                last_time_fetched = last_idle_time;
            }
            System.out.println("[MonkeyServer] idle fetch " + last_time_fetched);
            time_current = System.currentTimeMillis();
            if (last_time_fetched == -1 /* crash */
                  || fromMillis < last_time_fetched /* caught idle */
                  || time_current >= time_end /* timeout */) {
                break;
            }
            synchronized (this) {
                try {
                    if (time_end - time_current > 10000) {
                        wait(10000);
                    } else {
                        wait(time_end - time_current);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (last_time_fetched == -1 || time_current > time_end)
            return -1;
        return last_time_fetched;
    }

    public void writeInt32(int n) throws IOException {
        os.write(n & 0xff);
        os.write((n >> 8) & 0xff);
        os.write((n >> 16) & 0xff);
        os.write((n >> 24) & 0xff);
    }

    public static void writeInt32(OutputStream os, int n) throws IOException {
        os.write(n & 0xff);
        os.write((n >> 8) & 0xff);
        os.write((n >> 16) & 0xff);
        os.write((n >> 24) & 0xff);
    }

    public int readInt32() throws IOException {
        // Read idle time!
        int byte_written = 0;
        int cur_written;
        while (byte_written < 4) {
            cur_written = is.read(buffer, byte_written, 4-byte_written);
            if (cur_written == -1) {
                throw new IOException("readInt32");
            }

            byte_written += cur_written;
        }

        int ret = ((buffer[0] & 0xFF)  | ((buffer[1] & 0xFF) << 8) | ((buffer[2] & 0xFF) << 16) | ((buffer[3] & 0xFF) << 24));
        return ret;
    }

    public long readLong() throws IOException {
        // Read idle time!
        int byte_written = 0;
        int cur_written;
        while (byte_written < 8) {
            cur_written = is.read(buffer, byte_written, 8-byte_written);
            if (cur_written == -1) {
                throw new IOException("readInt32");
            }

            byte_written += cur_written;
        }

        long ret = (((long)buffer[0] & 0xFF)  | (((long)buffer[1] & 0xFF) << 8) | (((long)buffer[2] & 0xFF) << 16) | (((long)buffer[3] & 0xFF) << 24)
            | (((long)buffer[4] & 0xFF) << 32) | (((long)buffer[5] & 0xFF) << 40) | (((long)buffer[6] & 0xFF) << 48) | (((long)buffer[7] & 0xFF) << 56));
        return ret;
    }

    public String readMTDirectory() throws IOException {
        int length = readInt32();
        int byte_written = 0;
        int cur_written;
        while (byte_written < length) {
            cur_written = is.read(buffer, byte_written, length-byte_written);
            if (cur_written == -1) {
                throw new IOException("readInt32");
            }

            byte_written += cur_written;
        }

        buffer[length] = 0;
        String ret = new String(buffer, 0, length, "UTF-8");
        return ret;
    }

    // called from thread with MonkeySourceApe
    public synchronized boolean metTargetMethods(long timestamp) {
        boolean ret = last_target_time > timestamp;
        if (ret)
            System.out.println("[MonkeyServer] method_id " + last_method_id + " timestamp " + last_target_time);
        return ret;
    }


    public synchronized void waitFirstConnection() {
        while (connection_cnt == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                System.out.println("[MonkeyServer] interrupt");
                Thread.currentThread().interrupt();
            }
        }
        for (String directory: moved_directories) {
            System.out.println("[APE_MT] mt data " + directory);
        }
        moved_directories.clear();
    }

    public void moveMTDirectory(String directory) {
        // directory: /data/data/{package_name}/mt_data/{num}/
        // move the directory to /data/ape/mt_data/{num}
        File mt_data = new File(directory);
        String[] part = directory.split("/");
        if (part.length != 6) {
            throw new RuntimeException("Unknown directory " + directory);
        }

        String dest = String.format("/data/ape/mt_data/%s", part[5]);
        if (mt_data.renameTo(new File(dest))) {
            serverlog_pw.println(String.format("rename done %s %s", directory, dest));
        } else {
            serverlog_pw.println(String.format("rename failed %s %s", directory, dest));
        }
        synchronized(this) {
            moved_directories.add(dest);
        }
    }

    @Override
    public void run() {
        /* It may be enough to one-to-one with client */
        String directory = null;
        while (is_running) {
            // accept loop
            while (true) {
                try {
                    if (directory != null) { moveMTDirectory(directory); directory = null; }
                    socket = lss.accept();
                    is = socket.getInputStream();
                    os = socket.getOutputStream();
                    serverlog_pw.println(String.format("%d New connection #%d established", System.currentTimeMillis(), connection_cnt));
                    synchronized (this) {
                        connection_cnt += 1;
                        notifyAll();
                    }
                    break;
                } catch (IOException e) {
                    // accept timeout
                    if (!is_running)
                        return;
                }
            }
            try {
                // send target methods
                int hsval = readInt32();
                if (hsval != kHandShake) {
                    serverlog_pw.println(String.format("%d Handshake failed %d", System.currentTimeMillis(), Integer.toHexString(hsval)));
                    serverlog_pw.close();
                    throw new RuntimeException("handshake");
                }
                writeInt32(kHandShake);
                mainTid = readInt32();
                directory = readMTDirectory();
                writeInt32(target_methods.size()); // size could be zero
                serverlog_pw.println(String.format("%d Handshake success", System.currentTimeMillis()));
                for (TargetMethod target : target_methods) {
                    target.writeTo(os);
                }
            } catch (IOException e) {
                serverlog_pw.println(String.format("Sending target methods failed, currentTimeMillis  = %d", System.currentTimeMillis()));
                e.printStackTrace(serverlog_pw);
                synchronized (this) {
                    last_idle_time = -1;
                    connection_cnt = 0;
                    mainTid = -1;
                }
                continue;
            }

            while (is_running) {
                int id;
                try {
                    id = readInt32();
                } catch (IOException e) {
                    serverlog_pw.println(String.format("%d IOException on read id %s", System.currentTimeMillis(), e.getMessage()));
                    try {
                        socket.close();
                    } catch (IOException e2) {}
                    synchronized (this) {
                        last_idle_time = -1;
                        connection_cnt = 0;
                        mainTid = -1;
                    }
                    break;
                }

                try {
                    long tmp;
                    switch (id) {
                        case kTargetEntered:
                        case kTargetExited:
                        case kTargetUnwind:
                            // mask last action specialty...
                            int tid = readInt32(); // unused now
                            int method_id = readInt32();
                            if (method_id < 0 || method_id >= target_methods.size()) {
                                serverlog_pw.println(String.format("%d Wrong method id received: %x", System.currentTimeMillis(), method_id));
                                serverlog_pw.close();
                                throw new RuntimeException("Unknown method id " + method_id);
                            }
                            // read timestamp
                            tmp = readLong();
                            if (!mainThreadOnly || tid == mainTid) {
                                synchronized (this) {
                                    last_target_time = tmp;
                                    last_method_id = method_id;
                                }
                            }
                            // TargetMethod method = target_methods.get(method_id);
                            // if (id == kTargetEntered) {
                            //     serverlog_pw.println(String.format("Timestamp %d (tid=%d): method %s:%s[%s] entered", tmp, tid,
                            //         method.clsname, method.mtdname, method.signature, tid));
                            // } else if (id == kTargetExited) {
                            //     serverlog_pw.println(String.format("Timestamp %d (tid=%d): method %s:%s[%s] exited", tmp, tid,
                            //         method.clsname, method.mtdname, method.signature, tid));
                            // } else {
                            //     serverlog_pw.println(String.format("Timestamp %d (tid=%d): method %s:%s[%s] unwind", tmp, tid,
                            //         method.clsname, method.mtdname, method.signature, tid));
                            // }
                            break;
                        case kIdle:
                            // store idle time
                            tmp = readLong();
                            synchronized (this) {
                                last_idle_time = tmp;
                                notify();
                            }
                            break;
                        default:
                            serverlog_pw.println(String.format("%d Unknown id received %x", System.currentTimeMillis(), id));
                            serverlog_pw.close();
                            throw new RuntimeException("Unknown id");
                    }
                } catch (IOException e) {
                    serverlog_pw.println(String.format("%d IOException %s on read id %d", System.currentTimeMillis(), e.getMessage(), id));
                    try {
                        socket.close();
                    } catch (IOException e2) {}
                    synchronized (this) {
                        last_idle_time = -1;
                        connection_cnt = 0;
                        mainTid = -1;
                    }
                    break;
                }
            }

            // Socket is broken, wait for new accept
        }
    }
}
