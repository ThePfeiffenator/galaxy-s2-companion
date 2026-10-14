package org.retrobridge.gmail2012.provider;
import android.content.*;import android.database.*;import java.util.*;
public class MailEngine {
 MailStore mMailStore;
 public static MailEngine getOrMakeMailEngineSync(Context c,String a){return null;}
 Context getContext(){return null;}
 public String getAccountName(){return null;}
 public void blockUntilBackgroundTasksComplete(){}
 public void requestSync(){}
 MailSync.Message getMessage(long id,boolean attachments){return null;}
 public Gmail.LabelMap getLabelMap(){return null;}
 public void sendNotificationIntents(boolean b){}
 public long handleConversation(MailSync.Conversation c,long q,MailCore.Label l){return 0;}
}
interface MailStore {
 MailCore.Label getOrAddLabel(long id);
 MailCore.Label getOrAddLabel(String name);
 void renameLabel(MailCore.Label l,String canonical,String name,int color,String visible);
 void setLabels(Map<Long,LabelInfo> labels);
 void setSettings(Map<String,String> settings,boolean changed);
 void addOrUpdateMessage(MailSync.Message m,long q);
 void handleConversation(MailSync.Conversation c);
 void notifyConversationChanged(long id,MailSync.SyncRationale reason);
 void updateSentOrSavedMessage(long oldId,long newId,long conversationId);
 void prepare();void commit();
 public static class LabelInfo {
  public LabelInfo(String c,String n,int total,int unread,int color,String v){}
 }
}
class MailCore {public static class Label {public long id;public String canonicalName;}}
class MailSync {
 public enum SyncRationale {LABEL,DURATION,NONE,UNKNOWN,LOCAL_CHANGE}
 public static class Message {
  public String body,customFromAddress,listInfo,snippet,subject;
  public boolean bodyEmbedsExternalResources,clientCreated,forward,includeQuotedText;
  public long conversationId,dateReceivedMs,dateSentMs,messageId,quoteStartPos,refMessageId,refMsgId;
  public Gmail.PersonalLevel personalLevel;
  public String fromAddress="";
  public List<String> toAddresses,ccAddresses,bccAddresses,replyToAddresses;
  public Set<Long> labelIds;public List<Gmail.Attachment> attachments;
 }
 public static class Conversation {
  public long conversationId,date,maxMessageId,sortMessageId;
  public String fromAddress,snippet,subject;
  public boolean hasAttachments;public Set<Long> labelIds;public int numMessages;public Gmail.PersonalLevel personalLevel;
 }
}
class Gmail {
 public enum PersonalLevel {NOT_TO_ME,TO_ME_AND_OTHERS,ONLY_TO_ME}
 public static class Attachment {public String cachedContent,contentType,name,originExtras,partId,simpleContentType;public int size;public AttachmentOrigin origin;}
 public enum AttachmentOrigin {SERVER_ATTACHMENT,LOCAL_FILE}
 public enum AttachmentRendition {BEST,SIMPLE}
 public static class LabelMap {public boolean labelsSynced(){return false;}}
}
class CompactSenderInstructions {public void addMessage(String from,boolean draft,boolean unread,boolean sent,boolean outbox,boolean error){} public String toInstructionString(int max){return null;}}
