package org.retrobridge.callprobe;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Pure parsing: no Android dependency and no logging of call identities. */
final class HfpProtocol {
    static final Pattern INDICATOR = Pattern.compile("\\(\\s*\"([^\"]+)\"\\s*,\\s*\\(([0-9,\\-\\s]+)\\)\\s*\\)");
    static final class Call {
        final int index, direction, state, mode, multiparty, type;
        final String number;
        Call(int i, int d, int s, int m, int p, int t, String n) {
            index=i; direction=d; state=s; mode=m; multiparty=p; type=t; number=n;
        }
    }
    static int integer(String value, int low, int high) throws IOException {
        try {
            if (!value.matches("[0-9]{1,10}")) throw new NumberFormatException();
            int n=Integer.parseInt(value);
            if (n<low || n>high) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) { throw new IOException("Invalid numeric field"); }
    }
    static String safeNumber(String value) {
        return value != null && value.matches("\\+?[0-9]{1,40}") ? value : "";
    }
    static List<String> indicatorNames(String line) throws IOException {
        if (!line.startsWith("+CIND:")) throw new IOException("Missing indicators");
        String text=line.substring(6).trim(); Matcher match=INDICATOR.matcher(text);
        List<String> names=new ArrayList<String>(); int end=0;
        while (match.find()) {
            String separator=text.substring(end,match.start()).trim();
            if (!separator.equals(names.isEmpty() ? "" : ",")) throw new IOException("Malformed indicators");
            String name=match.group(1).toLowerCase(Locale.US);
            if (names.contains(name)) throw new IOException("Duplicate indicator");
            names.add(name); end=match.end();
        }
        if (names.size()>32 || !text.substring(end).trim().isEmpty() ||
            !names.containsAll(Arrays.asList("call","callsetup","service","signal","roam")))
            throw new IOException("Incomplete indicators");
        return Collections.unmodifiableList(names);
    }
    static int indicatorValue(String name, String text) throws IOException {
        int max = name.equals("callsetup") ? 3 : name.equals("callheld") ? 2 :
            name.equals("signal") || name.equals("battchg") ? 5 :
            name.equals("call") || name.equals("service") || name.equals("roam") ? 1 : 65535;
        return integer(text.trim(),0,max);
    }
    static Map<String,Integer> indicatorValues(List<String> names, String line) throws IOException {
        if (!line.startsWith("+CIND:")) throw new IOException("Missing indicator values");
        String[] fields=line.substring(6).trim().split(",",-1);
        if(fields.length!=names.size()) throw new IOException("Indicator count mismatch");
        Map<String,Integer> result=new HashMap<String,Integer>();
        for(int i=0;i<fields.length;i++) result.put(names.get(i),indicatorValue(names.get(i),fields[i]));
        return result;
    }
    static List<String> csv(String text) throws IOException {
        List<String> fields=new ArrayList<String>(); StringBuilder value=new StringBuilder();
        boolean quoted=false, ended=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='"') {
                if(quoted) { quoted=false; ended=true; }
                else if(value.toString().trim().isEmpty() && !ended) quoted=true;
                else throw new IOException("Malformed quoted field");
            } else if(c==',' && !quoted) {
                fields.add(value.toString().trim()); value.setLength(0); ended=false;
            } else {
                if(c<' ' || (ended && c!=' ')) throw new IOException("Invalid field character");
                value.append(c);
            }
        }
        if(quoted) throw new IOException("Unclosed quoted field");
        fields.add(value.toString().trim()); return fields;
    }
    static List<Call> calls(List<String> lines) throws IOException {
        List<Call> calls=new ArrayList<Call>(); Set<Integer> indices=new HashSet<Integer>();
        for(String line:lines) {
            if(!line.startsWith("+CLCC:")) continue;
            List<String> f=csv(line.substring(6).trim());
            if(f.size()!=5 && f.size()!=7 && f.size()!=8) throw new IOException("Malformed call row");
            int index=integer(f.get(0),1,255);
            if(!indices.add(index) || calls.size()==8) throw new IOException("Duplicate or excessive calls");
            calls.add(new Call(index,integer(f.get(1),0,1),integer(f.get(2),0,6),
                integer(f.get(3),0,2),integer(f.get(4),0,1),
                f.size()>=7?integer(f.get(6),0,255):129,f.size()>=7?safeNumber(f.get(5)):""));
        }
        return Collections.unmodifiableList(calls);
    }
    static String callFrame(List<Call> calls) {
        StringBuilder frame=new StringBuilder("CALLS\t").append(calls.size()).append('\n');
        for(Call c:calls) frame.append("CALL\t").append(c.index).append('\t').append(c.direction)
            .append('\t').append(c.state).append('\t').append(c.mode).append('\t').append(c.multiparty)
            .append('\t').append(c.type).append('\t').append(c.number).append('\n');
        return frame.toString();
    }
}
