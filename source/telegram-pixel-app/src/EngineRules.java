package org.retrobridge.galaxychat.pixel;

import org.json.JSONObject;

/** Small, fail-closed decisions shared by the engine and host checks. */
final class EngineRules {
    private EngineRules() {}
    static boolean supportedReply(JSONObject message,JSONObject properties) {
        if(!properties.optBoolean("can_be_replied",false))return false;
        if(message.optJSONObject("self_destruct_type")!=null||message.optDouble("auto_delete_in",0)>0||message.optJSONObject("ephemeral_content")!=null)return false;
        JSONObject topic=message.optJSONObject("topic_id");
        if(topic==null)return true;
        String type=topic.optString("@type");
        return type.equals("messageTopicThread")||type.equals("messageTopicSavedMessages");
    }
    static boolean canSend(JSONObject defaults,JSONObject status) {
        return canSend(defaults,status,"can_send_basic_messages");
    }
    static boolean canSend(JSONObject defaults,JSONObject status,String permission) {
        boolean basic=defaults!=null&&defaults.optBoolean(permission,false);
        if(status==null)return basic; // Private chats use their own chat permissions.
        String type=status.optString("@type");
        if(type.equals("chatMemberStatusCreator"))return status.optBoolean("is_member",false);
        if(type.equals("chatMemberStatusAdministrator"))return true;
        if(type.equals("chatMemberStatusMember"))return basic;
        JSONObject own=status.optJSONObject("permissions");
        return type.equals("chatMemberStatusRestricted")&&status.optBoolean("is_member",false)&&basic&&own!=null&&own.optBoolean(permission,false);
    }
    static boolean older(long message,long before) {return message>0&&(before==0||message<before);}
    static boolean moreHistory(long last,long before) {return older(last,before);}
}
