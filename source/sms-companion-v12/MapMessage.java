package org.retrobridge.callprobe;

/** Immutable data only. Never log this object or its fields. Null means not supplied. */
public final class MapMessage {
    public final String handle, datetime, address, sender, subject, body, folder, type;
    public final String mseTime;
    /** -1 when no trustworthy offset is available. Current MSE offset is marked separately. */
    public final long timestampMillis;
    public final boolean timestampUsesMseOffset;
    public MapMessage(String handle, String datetime, String sender, String subject,
                      String body, String folder, String type) {
        this(handle,datetime,sender,subject,body,folder,type,null,-1,false);
    }
    public MapMessage(String handle, String datetime, String sender, String subject,
                      String body, String folder, String type, String mseTime,
                      long timestampMillis, boolean timestampUsesMseOffset) {
        this.handle=handle; this.datetime=datetime; this.address=sender; this.sender=sender;
        this.subject=subject; this.body=body; this.folder=folder; this.type=type;
        this.mseTime=mseTime;this.timestampMillis=timestampMillis;this.timestampUsesMseOffset=timestampUsesMseOffset;
    }
}
