package com.android.commands.monkey.ape;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalServerSocket;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;
import com.android.commands.monkey.ape.utils.Config;

import java.lang.Runnable;
import java.lang.RuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Communicate with MiniTrace
 */
public class MonkeyServer implements Runnable {
    class TargetMethod {
        public String clsname;
        public String mtdname;
        public String signature;
        public int mtdFlag;

        public TargetMethod (String clsname, String mtdname, String signature, int mtdFlag) {
            this.clsname = clsname;
            this.mtdname = mtdname;
            this.signature = signature;
            this.mtdFlag = mtdFlag;
            if ((mtdFlag & (~(kMtdFlagEntered | kMtdFlagExited | kMtdFlagUnroll))) != 0)
                throw new RuntimeException("flag should be masked with 7");
        }
    }

    private static final String SOCK_ADDRESS = "/dev/mt/ape";
    private static MonkeyServer instance = null;

    private static final int kHandShake       = 0x0abeabe0;
    private static final int kTargetEntered   = 0xabeabe01; // 4byte
    private static final int kTargetExited    = 0xabeabe02; // 4byte
    private static final int kTargetUnwind    = 0xabeabe03; // 4byte
    private static final int kIdle            = 0xabe0de04; // 8byte

    private static final int kMtdFlagEntered  = 0x00000001;
    private static final int kMtdFlagExited   = 0x00000002;
    private static final int kMtdFlagUnroll   = 0x00000004;

    private LocalServerSocket lss;
    private int value = 0;

    // connection-specific values
    private LocalSocket socket;
    private InputStream is;
    private OutputStream os;

    // buffer to read 8~12 bytes
    private byte[] buffer;

    // Store method targets
    private List<TargetMethod> target_methods;
    private boolean target_taken;

    // Store time for last idle time
    private long last_idle_time; // must be protected with lock

    // Store time for last targeting method met
    private long last_target_time; // must be protected with lock

    private int connection_cnt;

    private MonkeyServer() throws IOException {
        lss = new LocalServerSocket(SOCK_ADDRESS);
        last_idle_time = 0;
        last_target_time = 0;
        connection_cnt = 0;
        buffer = new byte[8];
        parseTargetMtds();
    }

    public static void makeInstance() throws IOException {
        instance = new MonkeyServer();
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
            target_methods.add(new TargetMethod(tokens[0], tokens[1], tokens[2], Integer.parseInt(tokens[3])));
        }
        System.out.println("[MonkeyServer] Total " + target_methods.size() + " methods are targeted");
    }

    public void alertCrash() {
        synchronized (this) {
            last_idle_time = -1; // crashed
            last_target_time = -1;
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

    public int readInt32() throws IOException {
        // Read idle time!
        int byte_written = 0;
        int cur_written;
        while (byte_written < 4) {
            try {
                cur_written = is.read(buffer, byte_written, 4-byte_written);
            } catch (IOException e) {
                cur_written = -1;
            }
            if (cur_written == -1) {
                byte_written = -1;
                break;
            }

            byte_written += cur_written;        }
        if (byte_written == -1)
            throw new IOException("readint32");

        int ret = ((buffer[0] & 0xFF)  | ((buffer[1] & 0xFF) << 8) | ((buffer[2] & 0xFF) << 16) | ((buffer[3] & 0xFF) << 24));
        return ret;
    }

    // called from thread with MonkeySourceApe
    public synchronized boolean metTargetMethods(long timestamp) {
        return last_target_time > timestamp;
    }

    @Override
    public void run() {
        /* It may be enough to one-to-one with client */
        while (true) {
            try {
                socket = lss.accept();
                is = socket.getInputStream();
                os = socket.getOutputStream();
                connection_cnt += 1;
                System.out.println("[MonkeyServer] New connection #" + connection_cnt + "  established");
            } catch (IOException e) {
                throw new RuntimeException("accept");
            }
            try {
                // send target methods
                writeInt32(kHandShake);
                int hsval = readInt32();
                if (hsval != kHandShake) {
                    System.out.println("[MonkeyServer] Handshake failed " + Integer.toHexString(hsval));
                    throw new RuntimeException("handshake");
                }
                writeInt32(target_methods.size()); // size could be zero
                for (TargetMethod target : target_methods) {
                    // write string
                    writeInt32(target.clsname.length());
                    os.write(target.clsname.getBytes("UTF-8"));
                    writeInt32(target.mtdname.length());
                    os.write(target.mtdname.getBytes("UTF-8"));
                    writeInt32(target.signature.length());
                    os.write(target.signature.getBytes("UTF-8"));
                    writeInt32(target.mtdFlag);
                }
            } catch (IOException e) {
                throw new RuntimeException("write methods");
            }

            while (true) {
                int id;
                try {
                    id = readInt32();
                } catch (IOException e) {
                    System.out.println("[MonkeyServer] IOException " + e.getMessage());
                    System.out.println("[MonkeyServer] The target seems to be terminated");
                    try {
                        socket.close();
                    } catch (IOException e2) {}
                    synchronized (this) {
                        last_idle_time = -1;
                        last_target_time = -1;
                    }
                    break;
                }

                try {
                    switch (id) {
                        case kTargetEntered:
                        case kTargetExited:
                        case kTargetUnwind:
                            // mask last action specialty...
                            // @TODO failing on readInt32 should cause closing of original socket and starting new loop
                            int method_id = readInt32(); // unused now
                            if (method_id < 0 || method_id >= target_methods.size()) {
                                System.out.println("[MonkeyServer] wrong method id received: " + Integer.toHexString(method_id));
                                throw new RuntimeException("Unknown method id");
                            }
                            System.out.println("[MonkeyServer] Method invocation observed: method_id " + method_id);
                            synchronized (this) {
                                last_target_time = System.currentTimeMillis();
                            }
                            break;
                        case kIdle:
                            // store idle time
                            long tmp = (long)readInt32() + ((long)readInt32() << 32);
                            synchronized (this) {
                                last_idle_time = tmp;
                                System.out.println("[MonkeyServer] Idle time received: " +last_idle_time);
                                notify();
                            }
                            break;
                        default:
                            System.out.println("[MonkeyServer] Unknown id received, " + Integer.toHexString(id));
                            throw new RuntimeException("Unknown id");
                    }
                } catch (IOException e) {
                    System.out.println("[MonkeyServer] IOException " + e.getMessage());
                    System.out.println("[MonkeyServer] The target seems to be terminated");
                    try {
                        socket.close();
                    } catch (IOException e2) {}
                    synchronized (this) {
                        last_idle_time = -1;
                        last_target_time = -1;
                    }
                    break;
                }
            }

            // Socket is broken, wait for new accept
        }
    }
}