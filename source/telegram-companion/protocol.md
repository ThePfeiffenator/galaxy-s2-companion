# Galaxy Chat companion protocol 1

Protocol 1, updated for automatic startup 2026-10-12. Separate messaging apps.
RFCOMM service UUID `bf067b70-841f-4a63-93ea-724767ddd0bd`, service name `Galaxy Chat v1`.
Pixel address `@PIXEL_BLUETOOTH@`; S2 address `@S2_BLUETOOTH@`.
Both sockets use authenticated/encrypted Bluetooth and recheck exact bonded peer.
The saved enabled choice resumes connections on boot, package update, sticky
service recovery and Bluetooth availability. Pixel waits for first unlock so
credentials remain encrypted. Explicit Pause disables automatic connection until
Start/Connect. S2 grants the call bridge a short boot grace period. Pixel refuses application traffic
unless its HEADSET profile reports this S2 connected; loss closes this socket.

## Framing and application authentication

Shared implementation: `Wire.java`, package `org.retrobridge.galaxychat.protocol`.
Every frame has a four-byte unsigned big-endian length followed by UTF-8 JSON;
length must be 2..65536. Application payload UTF-8 is limited to 44000 bytes.
Reject malformed UTF-8, oversized/truncated frames, unexpected keys/version,
incorrect proof/session/sequence, or replay by closing the socket. Transport owner
closes socket after 15s incomplete handshake, 35s incomplete frame/request, and on
bounded output queue overflow. Idle peers exchange STATUS at most every 20s.

One application-pair key is 32 bytes from the OS random source, stored privately;
Pixel shows its standard padded Base64 form for local S2 entry. It is never sent
over the Bluetooth channel. This is a separate app key, not a Telegram credential.
Nonces are 32 random bytes, standard padded Base64 without whitespace. Session ID
is a fresh canonical lowercase UUID. ASCII HMAC transcripts are exact, with `\n`
between fields and no trailing newline; MAC is Base64 HMAC-SHA256 with pair key.

1. S2: `{v:1,k:"HELLO",cn:<clientNonce>}`.
2. Pixel: `{v:1,k:"CHALLENGE",cn,sn,sid,mac}`. MAC transcript
   `GC1\nserver\n<sid>\n<cn>\n<sn>`.
3. S2 verifies server proof and returns `{v:1,k:"PROOF",mac}` with transcript
   `GC1\nclient\n<sid>\n<cn>\n<sn>`.
4. Thereafter both sides use `{v:1,k:"DATA",sid,seq,body,mac}`. `seq` is a positive
   decimal string, starts at 1 independently per direction and increments by one.
   `body` is Base64 of the exact UTF-8 application JSON. MAC transcript is
   `GC1\nframe\n<sid>\n<C|S>\n<seq>\n<body>`, where C means S2 to Pixel.
   Verify MAC before interpreting body. No JSON canonicalization is required.

## Common JSON shapes

IDs for chats/messages/orders/events are decimal strings, never JS floats.
Request IDs are `[A-Za-z0-9_-]{1,64}`; operation IDs are canonical lowercase UUIDs.
Timestamps are Unix seconds as integer numbers. Absent optional IDs use `"0"`.
Arrays and all strings have the limits below. Unknown operation/invalid arguments
return a fixed error; arbitrary TDLib methods are never exposed.

Request: `{kind:"request",id,method,params:{...}}`.
Success: `{kind:"response",id,ok:true,result:{...}}`.
Failure: `{kind:"response",id,ok:false,error:{code,message,retry_after:0}}`.
Codes: BAD_REQUEST, AUTH_REQUIRED, UNAVAILABLE, UNSUPPORTED, FORBIDDEN, RATE_LIMIT,
NOT_FOUND, CONFLICT, UNCERTAIN, INTERNAL. Messages are fixed user-facing summaries,
not raw exceptions or arbitrary TDLib response dumps. `retry_after` seconds when known.
First authenticated Pixel payload: `{kind:"ready",protocol:1,epoch,status}`.
Status: `{auth,connection,send_enabled,scope:"cloud_text",notice}`. Auth values:
SETUP_REQUIRED, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, WAIT_EMAIL, WAIT_EMAIL_CODE,
WAIT_OTHER, READY, CLOSED, ERROR. Credentials/login actions occur only on Pixel.

Chat: `{id,title,unread,last_text,last_date,order,archived,can_send,blocked_reason}`.
Title <=256 characters, last_text <=240, order decimal string. Blocked channel,
forum, secret/unsupported chats may appear in lists with a clear reason but their
history/send is not exposed until corresponding protocol support exists.
Message: `{id,chat_id,date,text,outgoing,sender,reply_to_id,edited,content_type}`.
Text <=16384 UTF-8 bytes; sender <=128 characters; `content_type` is text or a
fixed nontext placeholder type. Outgoing pending messages include `send_state`
PENDING/SENT/FAILED, if known. Reply references preserve exact IDs.

## Operations

* STATUS `{}` -> status.
* LIST_CHATS `{archived:false,limit:20,cursor:""}` ->
  `{chats:[Chat],next_cursor:"",has_more:false}`. Limit1..30. Cursor is opaque,
  <=256 chars, session epoch-bound; never interpret it on S2. Empty starts a page.
* GET_HISTORY `{chat_id,limit:20,before_id:"0"}` ->
  `{messages:[Message],next_before_id:"0",has_more:false}`. Limit1..30. Messages
  newest first. Pixel excludes the before_id itself and supplies the oldest
  returned ID for pagination. Size budget may shorten a page; it must not skip.
* OPEN_CHAT `{chat_id,open:true}` -> `{chat:Chat}`; closing passes false and returns `{}`. One open chat per
  companion peer. Disconnect closes it locally through TDLib.
* VIEW_MESSAGES `{chat_id,message_ids:[id...]}` -> `{}`; max50; only genuinely
  visible messages. Sends ordinary Telegram read state, not during background sync.
* TYPING `{chat_id,active:true}` -> `{}`; false cancels. S2 throttles to >=4s.
* SEND_TEXT `{operation_id,chat_id,text,reply_to_id:"0"}` -> SendState. Text1..4096
  Unicode code points and <=16384 UTF-8 bytes. No automatic sends or silent retry.
* QUERY_SEND `{operation_id}` -> SendState or NOT_FOUND. Allowed after reconnect.
* SUBSCRIBE_UPDATES `{epoch:"",after_seq:"0"}` -> `{epoch,next_seq,resync_required}`.
* RESYNC `{}` -> `{epoch,next_seq,status}`; caller refreshes chat/open history and
  queries all unresolved operation IDs, then subscribes from returned next_seq-1.

SendState: `{operation_id,state,chat_id,message_id,reason}`; state is SUBMITTING,
PENDING,SENT,FAILED,UNCERTAIN. Pixel commits UUID+content digest before TDLib send.
Same UUID/same content returns stored state; different content is CONFLICT. No
automatic second TDLib send. Process death across unknown submission becomes
UNCERTAIN. SENT means TDLib send succeeded, not that recipient read the message.
S2 stores operation before writing SEND, then uses QUERY_SEND after disconnect.
NOT_FOUND after an uncertain attempt permits inspection only, not automatic replay.

Event: `{kind:"event",epoch,seq,event,data}`. Retain latest128 events in Pixel
memory; sequence increases once per emitted event. On reconnect/restart/gap beyond
retention require RESYNC. Events: STATUS(status), CHAT_CHANGED(Chat),
MESSAGE_NEW(Message), MESSAGE_CHANGED(Message),
MESSAGES_DELETED({chat_id,message_ids}), SEND_CHANGED(SendState),
RESYNC_REQUIRED({reason}). Events are hints backed by TDLib requests, never a
reason to resend a message. Cache deletion/update events must be applied promptly.

## Persistence and implementation limits

Pixel stores encrypted API configuration/pair key/TDLib DB key via Android Keystore,
private TDLib data, and a durable no-replay send journal. S2 holds pair key,
bounded recent display cache/drafts, and its operation journal; no Telegram auth
keys/API hash reach S2. Android backups and exported service components disabled.
Version 0.2 adds cloud media (photos, videos, documents, stickers), avatars and
Saved Messages. Existing Secret Chats, channels without sponsored-message display,
forums/topics, paid messages, disappearing-content caching and account management
remain unsupported. Voice recording is deferred at the user request.

## Media methods (0.2)

UPLOAD_BEGIN {upload_id UUID,size 1..104857600,sha256,name,mime} -> {offset}.
UPLOAD_CHUNK {upload_id,offset,data Base64} -> {offset}; 16384 byte chunks.
Gaps, overlaps, changed bytes on a retry and range overflow fail. Completed
uploads must match their SHA-256 before send. Name/mime are bounded/sanitized.
SEND_MEDIA {operation_id,upload_id,chat_id,reply_to_id,caption,media_kind} and
SEND_STICKER {operation_id,chat_id,reply_to_id,file_id} use the durable journal.
Every kind is checked against the corresponding current chat permission.
MEDIA_INFO {chat_id,message_id,variant full/preview/icon} validates chat/message
on Pixel, then returns ready:false or an opaque 15-minute handle with metadata.
READ_MEDIA {token,offset,limit 1..16384} returns authenticated bounded chunks.
No remote paths or arbitrary Telegram requests are accepted. Media caches stay
private. Photos sent from S2 are JPEG, max 1600px; files capped at 100 MB.
STICKER_SETS / STICKERS {set_id,offset} return installed, recent and favorite
stickers in pages of 24. STICKER_PREVIEW takes only a recently listed sticker ID.
Animated stickers use a still thumbnail on S2; sending retains the original.
SAVED_MESSAGES returns the account's own private chat; no recipient guesswork.
