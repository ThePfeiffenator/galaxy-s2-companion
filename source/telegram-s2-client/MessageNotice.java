package org.retrobridge.galaxychat.s2;

import org.json.JSONObject;

/** Notification copy only; no network access or message persistence. */
final class MessageNotice {
    static String clean(String text, int limit) {
        String value=text==null?"":text.replaceAll("[\\r\\n\\t]+", " ").trim();
        if(value.length()<=limit)return value;
        int end=limit-1;
        if(end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;
        return value.substring(0,end)+"\u2026";
    }
    static String[] format(JSONObject chat, JSONObject message) {
        String sender=clean(message.optString("sender"),128);
        String title=chat==null?"":clean(chat.optString("title"),128);
        if(title.length()==0)title=sender.length()==0?"Telegram":sender;
        String body=clean(message.optString("text"),220);
        JSONObject media=message.optJSONObject("media");
        if(body.length()==0){
            String kind=media==null?message.optString("content_type"):media.optString("kind");
            if(kind.equals("photo")||kind.equals("messagePhoto"))body="Photo";
            else if(kind.equals("video")||kind.equals("messageVideo"))body="Video";
            else if(kind.equals("animation")||kind.equals("messageAnimation"))body="GIF";
            else if(kind.equals("sticker")||kind.equals("messageSticker"))body="Sticker";
            else if(kind.equals("voice")||kind.equals("messageVoiceNote"))body="Voice message";
            else if(kind.equals("audio")||kind.equals("messageAudio"))body="Audio";
            else if(kind.equals("document")||kind.equals("messageDocument"))body=media==null?"File":clean(media.optString("name","File"),160);
            else body="New message";
        }
        // Private chats normally have the same title and sender. Groups show both.
        if(sender.length()>0&&!sender.equals(title))body=sender+": "+body;
        return new String[]{title,clean(body,240)};
    }
}
