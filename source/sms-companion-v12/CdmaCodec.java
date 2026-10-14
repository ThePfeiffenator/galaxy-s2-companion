package org.retrobridge.callprobe;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/** Exact SPH-D710 FL24 RIL_CDMA_SMS_Message adapter. No I/O or logging. */
public final class CdmaCodec {
    public static final int STRUCT_SIZE = 372;
    public static final int MAX_BEARER = 255;
    private static final String PREFIX = "com.android.internal.telephony.";
    private static final String GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXT = "\f^{}\\[~]|€";

    private CdmaCodec() {}

    /** Safe diagnostic code only; no address, body, or framework exception text. */
    public static final class CodecException extends Exception {
        public final String code;
        CodecException(String code) { super(code); this.code = code; }
    }

    public static final class Outgoing {
        public final String number, text;
        public final String destination;
        public final String body;
        /** All three are zero for a single message; sequence is one-based otherwise. */
        public final int concatReference, total, sequence, messageId;
        public final boolean concatReferenceIsEightBits, deliveryAckRequested;
        private Outgoing(String address, String content, Meta m) {
            number = destination = address; text = body = content;
            concatReference = m.reference; total = m.total; sequence = m.sequence;
            messageId = m.messageId; concatReferenceIsEightBits = m.eightBits;
            deliveryAckRequested = m.deliveryAck;
        }
    }

    /** Decode a native outbound CDMA SUBMIT, not a Parcel or over-the-air PDU. */
    public static Outgoing decode(byte[] nativeMessage) throws CodecException {
        Wire w = readWire(nativeMessage);
        if (w.teleservice != 4098 && w.teleservice != 4101) fail("TELESERVICE");
        if (w.servicePresent != 0 || w.serviceCategory != 0) fail("BROADCAST_UNSUPPORTED");
        if (w.subaddressCount != 0) fail("SUBADDRESS_UNSUPPORTED");
        String destination = decodeAddress(w, true);
        validateBearer(w.bearer, 2);
        try {
            Api api = new Api();
            Meta m = api.decodeSamsung(toSamsungBearer(w.bearer, 2));
            if (m.type != 2 || m.body == null) fail("NOT_TEXT_SUBMIT");
            if (m.portAddressed) fail("PORT_ADDRESSED_UNSUPPORTED");
            validateText(m.body);
            return new Outgoing(destination, m.body, m);
        } catch (CodecException e) { throw e; }
          catch (Exception e) { throw new CodecException("FRAMEWORK_DECODE"); }
          catch (LinkageError e) { throw new CodecException("FRAMEWORK_UNAVAILABLE"); }
    }

    /** Encode one delivery; use encodeParts for a longer message. */
    public static byte[] encode(String sender, String text, long timestampMillis,
                                int messageId) throws CodecException {
        Plan plan = planText(text);
        if (plan.parts.length != 1) fail("MULTIPART_REQUIRED");
        return encodeParts(sender, text, timestampMillis, messageId, messageId & 255)[0];
    }

    /** Message IDs increment modulo 65536; the low byte of the first ID is the concat reference. */
    public static byte[][] encodeParts(String sender, String text, long timestampMillis,
                                       int firstMessageId) throws CodecException {
        return encodeParts(sender, text, timestampMillis, firstMessageId, firstMessageId & 255);
    }

    /** Encode every part before returning. Caller reserves the 8-bit ref until reassembly completes. */
    public static byte[][] encodeParts(String sender, String text, long timestampMillis,
                                       int firstMessageId, int concatReference) throws CodecException {
        checkU16(firstMessageId, "MESSAGE_ID");
        if (concatReference < 0 || concatReference > 255) fail("CONCAT_REFERENCE");
        validateSender(sender);
        Plan plan = planText(text);
        byte[] timestamp = timestampBytes(timestampMillis);
        try {
            Api api = new Api();
            Object address = api.parseAddress.invoke(null, sender);
            if (address == null) fail("SENDER_UNENCODABLE");
            byte[] digits = (byte[]) field(address, "origBytes");
            int count = integer(address, "numberOfDigits");
            if (digits == null || count < 1 || count > 36 || count != digits.length)
                fail("ADDRESS_LENGTH");
            int mode = integer(address, "digitMode");
            int numberMode = integer(address, "numberMode");
            int ton = integer(address, "ton");
            int planNumber = integer(address, "numberPlan");
            byte[][] result = new byte[plan.parts.length][];
            for (int i = 0; i < result.length; i++) {
                int id = (firstMessageId + i) & 65535;
                Object data = api.userData.newInstance();
                set(data, "payloadStr", plan.parts[i]);
                set(data, "msgEncoding", Integer.valueOf(plan.encoding));
                set(data, "msgEncodingSet", Boolean.TRUE);
                if (result.length > 1) {
                    // Six bytes including UDHL: even alignment avoids Samsung's
                    // encode16bitEms/decodeUtf16 odd-offset padding mismatch.
                    byte[] header = new byte[] {0, 3, (byte)concatReference,
                        (byte)result.length, (byte)(i + 1)};
                    set(data, "userDataHeader", api.fromHeader.invoke(null, new Object[] {header}));
                }
                Object bearer = api.bearerData.newInstance();
                set(bearer, "messageType", Integer.valueOf(1));
                set(bearer, "messageId", Integer.valueOf(id));
                set(bearer, "userData", data);
                // Stock default immediate display mode is 1; do not request flash display.
                byte[] encoded = (byte[])api.encode.invoke(null, bearer);
                if (encoded == null) fail("FRAMEWORK_ENCODE_REJECTED");
                byte[] withTime = appendTimestamp(encoded, timestamp);
                validateBearer(withTime, 1);
                byte[] samsungBearer = toSamsungBearer(withTime, 1);
                Meta check = api.decodeSamsung(samsungBearer);
                if (check.type != 1 || check.messageId != id || !plan.parts[i].equals(check.body)
                    || check.portAddressed || check.timestampMillis != (timestampMillis / 1000L) * 1000L)
                    fail("ROUND_TRIP_CONTENT");
                if (result.length > 1) {
                    if (check.reference != concatReference || check.total != result.length
                        || check.sequence != i + 1 || !check.eightBits) fail("ROUND_TRIP_CONCAT");
                } else if (check.total != 0) fail("ROUND_TRIP_CONCAT");
                result[i] = pack(4098, mode, numberMode, ton, planNumber, digits, samsungBearer);
            }
            return result;
        } catch (CodecException e) { throw e; }
          catch (Exception e) { throw new CodecException("FRAMEWORK_ENCODE"); }
          catch (LinkageError e) { throw new CodecException("FRAMEWORK_UNAVAILABLE"); }
    }

    private static Object field(Object target, String name) throws Exception {
        return target.getClass().getField(name).get(target);
    }
    private static int integer(Object target, String name) throws Exception {
        return target.getClass().getField(name).getInt(target);
    }
    private static boolean bool(Object target, String name) throws Exception {
        return target.getClass().getField(name).getBoolean(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        target.getClass().getField(name).set(target, value);
    }

    private static final class Api {
        final Class<?> bearerData = Class.forName(PREFIX + "cdma.sms.BearerData");
        final Class<?> userData = Class.forName(PREFIX + "cdma.sms.UserData");
        final Method decode = bearerData.getMethod("decode", byte[].class);
        final Method encode = bearerData.getMethod("encode", bearerData);
        final Method parseAddress = Class.forName(PREFIX + "cdma.sms.CdmaSmsAddress")
            .getMethod("parse", String.class);
        final Method fromHeader = Class.forName(PREFIX + "SmsHeader")
            .getMethod("fromByteArray", byte[].class);
        Api() throws Exception {}
        // Input is already adapted exactly once for the FL24 receive decoder.
        Meta decodeSamsung(byte[] data) throws Exception {
            Object bearer = decode.invoke(null, new Object[] {data});
            if (bearer == null) fail("FRAMEWORK_DECODE_REJECTED");
            Meta m = new Meta();
            m.type = integer(bearer, "messageType");
            m.messageId = integer(bearer, "messageId");
            checkU16(m.messageId, "MESSAGE_ID");
            m.deliveryAck = bool(bearer, "deliveryAckReq");
            Object user = field(bearer, "userData");
            if (user == null) fail("USER_DATA_MISSING");
            m.body = (String)field(user, "payloadStr");
            Object header = field(user, "userDataHeader");
            if (bool(bearer, "hasUserDataHeader") != (header != null)) fail("HEADER_MISMATCH");
            if (header != null) {
                // Samsung's SmsHeader parser silently discards malformed concat IEs;
                // validate their original bytes before trusting the reflected fields.
                byte[] payload = (byte[])field(user, "payload");
                validateUdh(payload);
                m.portAddressed = field(header, "portAddrs") != null;
                Object concat = field(header, "concatRef");
                if (concat != null) {
                    m.reference = integer(concat, "refNumber");
                    m.total = integer(concat, "msgCount");
                    m.sequence = integer(concat, "seqNumber");
                    m.eightBits = bool(concat, "isEightBits");
                    if (m.reference < 0 || m.reference > (m.eightBits ? 255 : 65535)
                        || m.total < 1 || m.total > 255 || m.sequence < 1 || m.sequence > m.total)
                        fail("CONCAT_INVALID");
                }
            }
            Object time = field(bearer, "msgCenterTimeStamp");
            if (time != null) m.timestampMillis = ((Long)time.getClass()
                .getMethod("toMillis", boolean.class).invoke(time, Boolean.TRUE)).longValue();
            return m;
        }
    }

    private static final class Meta {
        String body;
        int type, messageId, reference, total, sequence;
        boolean eightBits, deliveryAck, portAddressed;
        long timestampMillis = Long.MIN_VALUE;
    }

    static final class Wire {
        int teleservice, servicePresent, serviceCategory;
        int digitMode, numberMode, numberType, numberPlan, subaddressCount;
        byte[] digits, bearer;
    }

    static Wire readWire(byte[] bytes) throws CodecException {
        if (bytes == null || bytes.length != STRUCT_SIZE) fail("STRUCT_LENGTH");
        Wire w = new Wire();
        w.teleservice = getInt(bytes, 0); w.servicePresent = bytes[4] & 255;
        w.serviceCategory = getInt(bytes, 8); w.digitMode = getInt(bytes, 12);
        w.numberMode = getInt(bytes, 16); w.numberType = getInt(bytes, 20);
        w.numberPlan = getInt(bytes, 24);
        if (w.servicePresent > 1 || w.digitMode < 0 || w.digitMode > 1
            || w.numberMode < 0 || w.numberMode > 1 || w.numberType < 0 || w.numberType > 7
            || w.numberPlan < 0 || w.numberPlan > 15) fail("STRUCT_ENUM");
        int count = bytes[28] & 255;
        int subType = getInt(bytes, 68);
        w.subaddressCount = bytes[73] & 255;
        if (count < 1 || count > 36 || w.subaddressCount > 36) fail("ADDRESS_LENGTH");
        if ((subType != 0 && subType != 1) || (bytes[72] & 255) > 1) fail("SUBADDRESS_ENUM");
        int size = getInt(bytes, 112);
        if (size < 1 || size > MAX_BEARER) fail("BEARER_LENGTH");
        w.digits = Arrays.copyOfRange(bytes, 29, 29 + count);
        w.bearer = Arrays.copyOfRange(bytes, 116, 116 + size);
        return w;
    }

    static byte[] pack(int teleservice, int mode, int numberMode, int ton, int plan,
                       byte[] digits, byte[] bearer) throws CodecException {
        if (digits == null || digits.length < 1 || digits.length > 36) fail("ADDRESS_LENGTH");
        if (bearer == null || bearer.length < 1 || bearer.length > MAX_BEARER) fail("BEARER_LENGTH");
        byte[] wire = new byte[STRUCT_SIZE];
        putInt(wire, 0, teleservice); putInt(wire, 12, mode); putInt(wire, 16, numberMode);
        putInt(wire, 20, ton); putInt(wire, 24, plan);
        wire[28] = (byte)digits.length;
        System.arraycopy(digits, 0, wire, 29, digits.length);
        putInt(wire, 112, bearer.length);
        System.arraycopy(bearer, 0, wire, 116, bearer.length);
        Wire check = readWire(wire);
        decodeAddress(check, false);
        return wire;
    }

    static String decodeAddress(Wire w, boolean destination) throws CodecException {
        if (destination && w.numberMode != 0) fail("DESTINATION_NOT_TELEPHONE");
        StringBuilder s = new StringBuilder();
        for (byte b : w.digits) {
            int value = b & 255;
            char c;
            if (w.digitMode == 0) {
                if (value >= 1 && value <= 9) c = (char)('0' + value);
                else if (value == 10) c = '0';
                else if (value == 11) c = '*';
                else if (value == 12) c = '#';
                else { fail("ADDRESS_DTMF"); return null; }
            } else {
                if (value < 32 || value > 126) fail("ADDRESS_ASCII");
                c = (char)value;
            }
            if (destination && !(c >= '0' && c <= '9') && c != '*' && c != '#'
                && !(c == '+' && s.length() == 0)) fail("DESTINATION_NOT_TELEPHONE");
            s.append(c);
        }
        if (w.numberType == 1 && s.charAt(0) != '+') s.insert(0, '+');
        if (s.toString().equals("+")) fail("ADDRESS_EMPTY");
        return s.toString();
    }

    static void validateBearer(byte[] b, int type) throws CodecException {
        if (b == null || b.length == 0 || b.length > MAX_BEARER) fail("BEARER_LENGTH");
        boolean[] seen = new boolean[256];
        for (int p = 0; p < b.length;) {
            if (p + 2 > b.length) fail("BEARER_TLV");
            int id = b[p++] & 255, size = b[p++] & 255;
            if (p + size > b.length || seen[id]) fail("BEARER_TLV");
            seen[id] = true;
            if (id == 0 && (size != 3 || (b[p] & 255) >>> 4 != type)) fail("MESSAGE_TYPE");
            if (id == 1 && size < 2) fail("USER_DATA_MISSING");
            p += size;
        }
        if (!seen[0] || !seen[1]) fail("BEARER_REQUIRED_FIELD");
    }

    /**
     * FL24 encodeMessageId emits standard 3-byte data. Its receive decoder reads
     * those 24 bits PLUS a separate byte for hasUserDataHeader, without counting
     * that byte in the subparameter length. Preserve canonical TLVs until this
     * boundary; only reflection decode and incoming RIL delivery use the result.
     */
    static byte[] toSamsungBearer(byte[] canonical, int type) throws CodecException {
        validateBearer(canonical, type);
        if (canonical.length >= MAX_BEARER) fail("BEARER_LENGTH");
        int identifier = -1;
        for (int p = 0; p < canonical.length; p += 2 + (canonical[p + 1] & 255)) {
            if ((canonical[p] & 255) == 0) { identifier = p; break; }
        }
        if (identifier < 0) fail("BEARER_REQUIRED_FIELD");
        int after = identifier + 5;
        byte[] result = new byte[canonical.length + 1];
        System.arraycopy(canonical, 0, result, 0, after);
        result[after] = (byte)((canonical[identifier + 4] >>> 3) & 1);
        System.arraycopy(canonical, after, result, after + 1, canonical.length - after);
        return result;
    }

    static void validateUdh(byte[] payload) throws CodecException {
        if (payload == null || payload.length == 0) fail("UDH_LENGTH");
        int end = 1 + (payload[0] & 255);
        if (end > payload.length) fail("UDH_LENGTH");
        boolean concat = false;
        for (int p = 1; p < end;) {
            if (p + 2 > end) fail("UDH_LENGTH");
            int id = payload[p++] & 255, n = payload[p++] & 255;
            if (p + n > end) fail("UDH_LENGTH");
            if (id == 0 || id == 8) {
                int expected = id == 0 ? 3 : 4;
                if (concat || n != expected) fail("CONCAT_INVALID");
                concat = true;
                int count = payload[p + n - 2] & 255, sequence = payload[p + n - 1] & 255;
                if (count < 1 || sequence < 1 || sequence > count) fail("CONCAT_INVALID");
            }
            p += n;
        }
    }

    static byte[] appendTimestamp(byte[] bearer, byte[] timestamp) throws CodecException {
        validateBearer(bearer, 1);
        if (timestamp == null || timestamp.length != 6) fail("TIMESTAMP_LENGTH");
        for (int p = 0; p < bearer.length; p += 2 + (bearer[p + 1] & 255))
            if ((bearer[p] & 255) == 3) fail("TIMESTAMP_ALREADY_PRESENT");
        if (bearer.length + 8 > MAX_BEARER) fail("BEARER_LENGTH");
        byte[] result = Arrays.copyOf(bearer, bearer.length + 8);
        result[bearer.length] = 3; result[bearer.length + 1] = 6;
        System.arraycopy(timestamp, 0, result, bearer.length + 2, 6);
        return result;
    }

    static byte[] timestampBytes(long millis) throws CodecException {
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(millis);
        int year = c.get(Calendar.YEAR);
        if (c.get(Calendar.ERA) != GregorianCalendar.AD || year < 1996 || year > 2095)
            fail("TIMESTAMP_RANGE");
        return new byte[] {bcd(year % 100), bcd(c.get(Calendar.MONTH) + 1),
            bcd(c.get(Calendar.DAY_OF_MONTH)), bcd(c.get(Calendar.HOUR_OF_DAY)),
            bcd(c.get(Calendar.MINUTE)), bcd(c.get(Calendar.SECOND))};
    }
    private static byte bcd(int value) { return (byte)(((value / 10) << 4) | (value % 10)); }

    static final class Plan {
        final int encoding;
        final String[] parts;
        Plan(int encoding, String[] parts) { this.encoding = encoding; this.parts = parts; }
    }
    static Plan planText(String text) throws CodecException {
        validateText(text);
        if (text.length() > 32768) fail("TEXT_TOO_LONG");
        int septets = 0;
        boolean gsm = true;
        for (int i = 0; i < text.length(); i++) {
            int cost = gsmCost(text.charAt(i));
            if (cost == 0) { gsm = false; break; }
            septets += cost;
        }
        if ((gsm ? septets <= 160 : text.length() <= 70))
            return new Plan(gsm ? 9 : 4, new String[] {text});
        int limit = gsm ? 153 : 67;
        ArrayList<String> parts = new ArrayList<String>();
        int start = 0, cost = 0;
        for (int i = 0; i < text.length();) {
            int width = Character.isHighSurrogate(text.charAt(i)) ? 2 : 1;
            int nextCost = gsm ? gsmCost(text.charAt(i)) : width;
            if (cost + nextCost > limit) {
                parts.add(text.substring(start, i)); start = i; cost = 0;
                if (parts.size() >= 255) fail("TOO_MANY_PARTS");
            }
            cost += nextCost; i += width;
        }
        parts.add(text.substring(start));
        if (parts.size() > 255) fail("TOO_MANY_PARTS");
        return new Plan(gsm ? 9 : 4, parts.toArray(new String[parts.size()]));
    }
    private static int gsmCost(char c) {
        return GSM_BASIC.indexOf(c) >= 0 ? 1 : GSM_EXT.indexOf(c) >= 0 ? 2 : 0;
    }
    private static void validateText(String text) throws CodecException {
        if (text == null) fail("TEXT_NULL");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) fail("TEXT_SURROGATE");
            } else if (Character.isLowSurrogate(c)) fail("TEXT_SURROGATE");
        }
    }
    private static void validateSender(String sender) throws CodecException {
        if (sender == null || sender.length() == 0 || sender.length() > 72) fail("SENDER_LENGTH");
        for (int i = 0; i < sender.length(); i++)
            if (sender.charAt(i) < 32 || sender.charAt(i) > 126) fail("SENDER_ASCII");
    }
    private static int getInt(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8) | ((b[p + 2] & 255) << 16) | (b[p + 3] << 24);
    }
    private static void putInt(byte[] b, int p, int value) {
        for (int i = 0; i < 4; i++) b[p + i] = (byte)(value >>> (8 * i));
    }
    private static void checkU16(int value, String code) throws CodecException {
        if (value < 0 || value > 65535) fail(code);
    }
    private static void fail(String code) throws CodecException { throw new CodecException(code); }
}
