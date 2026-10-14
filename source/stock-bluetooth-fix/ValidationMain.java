package org.retrobridge.bluetoothvalidation;

import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Counts-only diagnostics. Never prints input property values or stack traces. */
public final class ValidationMain {
    private static native Object[] readProperties() throws IOException;
    private static native Object[] syntheticProperties(int count,int variant) throws IOException;
    private static String hash(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        InputStream input=new FileInputStream(file);
        try { byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count); }
        finally { input.close(); }
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.US,"%02x",b&255));return out.toString();
    }
    private static void verifyRuntime(String path,String wanted) throws Exception {
        File file=new File(path);
        if(!file.isAbsolute()||!file.getCanonicalPath().equals(path)||!wanted.matches("[0-9a-f]{64}"))throw new IOException("runtime argument");
        BufferedReader maps=new BufferedReader(new FileReader("/proc/self/maps"));String line;boolean found=false;
        try { while((line=maps.readLine())!=null) {
            if(line.endsWith("/libandroid_runtime.so")) {
                if(!line.endsWith(" "+path))throw new IOException("unexpected runtime mapping");
                found=true;
            }
        }}finally{maps.close();}
        if(!found||!wanted.equals(hash(file)))throw new IOException("runtime identity mismatch");
        System.out.println("RUNTIME_SHA256\t"+wanted);
    }
    private static Map<?,?> parseExact(String[] flat) throws Exception {
        Class<?> parser=Class.forName("android.server.BluetoothDeviceProperties");
        Class<?> service=Class.forName("android.server.BluetoothService");
        Constructor<?> constructor=parser.getDeclaredConstructor(service);constructor.setAccessible(true);
        Object instance=constructor.newInstance(new Object[]{null});
        Method add=parser.getDeclaredMethod("addProperties",String.class,String[].class);add.setAccessible(true);
        try {return (Map<?,?>)add.invoke(instance,"@PIXEL_BLUETOOTH@",flat);}
        catch(InvocationTargetException failure) {
            // Exact FL24 line80 calls mService only AFTER committing the parsed
            // map. Null service prevents any channel-cache/daemon activity.
            Throwable cause=failure.getCause();StackTraceElement[] trace=cause.getStackTrace();
            if(!(cause instanceof NullPointerException)||trace.length==0||
                !"android.server.BluetoothDeviceProperties".equals(trace[0].getClassName())||
                !"addProperties".equals(trace[0].getMethodName())||trace[0].getLineNumber()!=80)throw failure;
            Field field=parser.getDeclaredField("mPropertiesMap");field.setAccessible(true);
            Object parsed=((Map<?,?>)field.get(instance)).get("@PIXEL_BLUETOOTH@");
            if(!(parsed instanceof Map))throw new IOException("postparse map absent");
            return (Map<?,?>)parsed;
        }
    }
    public static void main(String[] args) {
        try {
            if(args.length!=4||!(args[3].equals("original")||args[3].equals("patched")))throw new IOException("arguments");
            verifyRuntime(args[1],args[2]);System.load(new File(args[0]).getCanonicalPath());
            if(args[3].equals("patched")) {
                int checks=0;
                for(int count:new int[]{0,1,2,5,16}) {
                    Object[] sample=syntheticProperties(count,0);
                    if(!Arrays.equals((String[])sample[0],(String[])sample[1]))throw new IOException("synthetic value mismatch");
                    Map<?,?> map=parseExact((String[])sample[0]);
                    if(map.containsKey("Services")||!"true".equals(map.get("Paired")))throw new IOException("synthetic parser mismatch");
                    checks++;
                }
                if(syntheticProperties(5,1)!=null||syntheticProperties(5,2)!=null)throw new IOException("malformed acceptance");checks+=2;
                Object[] unrelated=syntheticProperties(5,3);
                if(!Arrays.equals((String[])unrelated[0],(String[])unrelated[1]))throw new IOException("unrelated table changed");checks++;
                System.out.println("SYNTHETIC_CASES\tPASS\t"+checks);
            }
            Object[] result=readProperties();String[] actual=(String[])result[0],reference=(String[])result[1];
            System.out.println("FLAT_COUNTS\tactual="+actual.length+"\treference="+reference.length);
            if(args[3].equals("original")) {
                try {parseExact(actual);throw new IOException("original parser unexpectedly succeeded");}
                catch(InvocationTargetException e){
                    if(!(e.getCause() instanceof ArrayIndexOutOfBoundsException))throw new IOException("unexpected original failure");
                    System.out.println("ORIGINAL_EXPECTED_ARRAY_BOUNDS_FAILURE\tPASS");
                }
            } else {
                if(!Arrays.equals(actual,reference))throw new IOException("property preservation mismatch");
                Map<?,?> parsed=parseExact(actual);String uuids=(String)parsed.get("UUIDs");
                if(uuids==null||!"true".equals(parsed.get("Paired"))||parsed.containsKey("Services"))throw new IOException("parsed invariants");
                int count=uuids.split(",").length;if(count!=19)throw new IOException("UUID count changed");
                System.out.println("PRESERVED_PROPERTY_ARRAY\tPASS");
                System.out.println("EXACT_STOCK_JAVA_PARSER\tPASS\tuuid_count="+count+"\tpaired=true\tservices_omitted=true");
            }
        }catch(Throwable failure){System.err.println("VALIDATION_ERROR\t"+failure.getClass().getSimpleName());System.exit(1);}
    }
}
