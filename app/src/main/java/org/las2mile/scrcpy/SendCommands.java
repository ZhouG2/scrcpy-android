package org.las2mile.scrcpy;

import android.content.Context;
import android.util.Log;

import org.las2mile.scrcpy.adblib.AdbBase64;
import org.las2mile.scrcpy.adblib.AdbConnection;
import org.las2mile.scrcpy.adblib.AdbCrypto;
import org.las2mile.scrcpy.adblib.AdbStream;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import static android.org.apache.commons.codec.binary.Base64.encodeBase64String;

//Uses code from https://github.com/Jolanrensen/ADBPlugin


public class SendCommands {

    private Thread thread = null;
    private Context context;
    private int status;
    public static String TAG = "SendCommands";

    public SendCommands() {

    }

    public static AdbBase64 getBase64Impl() {
        return new AdbBase64() {
            @Override
            public String encodeToString(byte[] arg0) {
                return encodeBase64String(arg0);
            }
        };
    }

    private AdbCrypto setupCrypto()
            throws NoSuchAlgorithmException, IOException {

        AdbCrypto c = null;
        try {
            FileInputStream privIn = context.openFileInput("priv.key");
            FileInputStream pubIn = context.openFileInput("pub.key");
            c = AdbCrypto.loadAdbKeyPair(getBase64Impl(), privIn, pubIn);
        } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException | NullPointerException e) {
            // Failed to read from file
            c = null;
        }


        if (c == null) {
            // We couldn't load a key, so let's generate a new one
            c = AdbCrypto.generateAdbKeyPair(getBase64Impl());

            // Save it
            FileOutputStream privOut = context.openFileOutput("priv.key", Context.MODE_PRIVATE);
            FileOutputStream pubOut = context.openFileOutput("pub.key", Context.MODE_PRIVATE);

            c.saveAdbKeyPair(privOut, pubOut);
            //Generated new keypair
        } else {
            //Loaded existing keypair
        }

        return c;
    }


    public int SendAdbCommands(Context context, final byte[] fileBase64, final String ip, String localip, int bitrate, int size) {
        this.context = context;
        status = 1;
        final StringBuilder command = new StringBuilder();
//        localip = "0.0.0.0";
        command.append(" CLASSPATH=/data/local/tmp/scrcpysrv.jar app_process / org.las2mile.scrcpy.Server ");
        command.append(" /" + localip + " " + Long.toString(size) + " " + Long.toString(bitrate) + ";");

        Thread thd = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "run: adbWrite:::");
                    adbWrite(ip, fileBase64, command.toString());
                } catch (IOException e) {
                    Log.e(TAG, "SendAdbCommands: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        thd.start();

        try {

            thd.join(300 * 1000);
//            thd.wait(30*1000);
        } catch (Exception e) {
            e.printStackTrace();
        }


        return status;
    }


    private void adbWrite(String ip, byte[] fileBase64, String command) throws IOException {

        AdbConnection adb = null;
        Socket sock = null;
        AdbCrypto crypto;
        AdbStream stream = null;

        try {
            crypto = setupCrypto();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return;
        } catch (IOException e) {
            e.printStackTrace();
            throw new IOException("Couldn't read/write keys");
        }

        try {
            String[] arr = ip.split(":");
            int port = 5555;
            if (arr.length > 1) {
                try {
                    port = Integer.valueOf(arr[1]);
                } catch (Exception e) {
                    port = 5555;
                }
            }

            sock = new Socket(arr[0], port);
        } catch (UnknownHostException e) {
            status = 2;
            throw new UnknownHostException(ip + " is no valid ip address");
        } catch (ConnectException e) {
            status = 2;
            throw new ConnectException("Device at " + ip + ":" + 5555 + " has no adb enabled or connection is refused");
        } catch (NoRouteToHostException e) {
            status = 2;
            throw new NoRouteToHostException("Couldn't find adb device at " + ip + ":" + 5555);
        } catch (IOException e) {
            e.printStackTrace();
            status = 2;
        }

        if (sock != null) {
            try {
                Log.i(TAG, "adbWrite: adb.connect");
                adb = AdbConnection.create(sock, crypto);
                adb.connect();
            } catch (IllegalStateException e) {
                Log.e(TAG, "adbWrite: ");
                e.printStackTrace();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                Log.e(TAG, "adbWrite: ");

                return;
            }
        }

        if (adb != null) {

            try {
                Log.i(TAG, "adbWrite: adb.open");
                stream = adb.open("shell:");
            } catch (IOException | InterruptedException e) {

                e.printStackTrace();
                return;
            }
        }



        if (stream != null) {


            try {

                stream.writeForResult(command + '\n');
                status = 0;
                return;
//                String rlt = stream.writeForResult("rm -f serverBase64" + '\n', 10000);
//                stream.write();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                return;
            }
        } else
            Log.i(TAG, "adbWrite: stream == null");


        if (stream != null) {
            int len = fileBase64.length;
            byte[] filePart = new byte[4056];
            int sourceOffset = 0;
            try {
                stream.write(" cd /data/local/tmp " + '\n');
                while (sourceOffset < len) {
                    if (len - sourceOffset >= 4056) {
                        System.arraycopy(fileBase64, sourceOffset, filePart, 0, 4056);  //Writing in 4KB pieces. 4096-40  ---> 40 Bytes for actual command text.
                        sourceOffset = sourceOffset + 4056;


                        String ServerBase64part = new String(filePart, "UTF-8");
                        Log.i(TAG, "adbWrite: Base64\n" + ServerBase64part);
                        stream.writeForResult(" echo " + ServerBase64part + " >> serverBase64" + '\n', 10000);

                    } else {
                        int rem = len - sourceOffset;
                        byte[] remPart = new byte[rem];
                        System.arraycopy(fileBase64, sourceOffset, remPart, 0, rem);
                        sourceOffset = sourceOffset + rem;
                        String ServerBase64part = new String(remPart, "UTF-8");
                        stream.writeForResult(" echo " + ServerBase64part + " >> serverBase64" + '\n', 10000);

                    }
                }
                stream.write(" base64 -d < serverBase64 > scrcpy-server.jar " + '\n');
                stream.write(command + '\n');
            } catch (IOException | InterruptedException e) {
                Log.e("adbWrite: ", "adbWrite failed!!!!! ");
                e.printStackTrace();
                return;
            }
        }
        Log.i(TAG, "adbWrite: Send Command Succeed");
        status = 0;

    }

}
