'use strict';
// Run privately in Termux. Input is an authorized player response; never log it.
const fs=require('fs'),path=require('path');
console.log=console.warn=console.error=()=>{};
const back=path.join(process.env.HOME,'yt2009/back');
process.chdir(back);
const sabr=require(path.join(back,'yt2009sabr'));
const proto=require(path.join(back,'proto/sabr_pb'));
const fetch=require(require.resolve('node-fetch',{paths:[back]}));
function safe(url){const u=new URL(url);return u.protocol==='https:'&&(u.hostname.endsWith('.googlevideo.com')||u.hostname.endsWith('.c.youtube.com'))&&!u.username&&!u.password&&(!u.port||u.port==='443');}
let stage='input',httpStatus,attempts=[];
async function main(){
 const input=JSON.parse(fs.readFileSync(0,'utf8')),p=input.player;
 const fmt=p.streamingData.adaptiveFormats.find(f=>f.itag===140&&!f.drmFamilies);
 if(!fmt)throw Error('audio_format');
 let url=p.streamingData.serverAbrStreamingUrl;
 const config=p.playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig.videoPlaybackUstreamerConfig;
 stage='proto';
 const payload=proto.root.deserializeBinary(sabr.createProto(0,0,140,Number(fmt.lastModified),'',config,Math.max(0,Math.floor(input.offset*1000)),''));
 payload.clearPlayablevideoitagList();
 const origin=payload.getPlayerreqList()[0];
 origin.clearServiceintegritydimensionsList();
 const client=origin.getClientList()[0];client.setClientnumber(7);client.setClientversion(p._galaxyClientVersion||'7.20251001.18.00');
 for(let attempt=0;attempt<3;attempt++){
  if(!safe(url))throw Error('source_host');
  stage='fetch';
  const headers={'Content-Type':'application/x-protobuf'};
  if(input.token&&new URL(url).hostname.endsWith('.c.youtube.com'))headers.Authorization='Bearer '+input.token;
  const response=await fetch(url,{method:'POST',headers,body:Buffer.from(payload.serializeBinary()),redirect:'manual',timeout:15000,size:4*1024*1024});
  httpStatus=response.status;
  attempts.push({status:httpStatus,host:new URL(url).hostname});
  if([301,302,303,307,308].includes(response.status)){url=response.headers.get('location');continue;}
  if(response.status!==200)throw Error('source_status');
  const body=await response.buffer();let next;
  stage='parse';
  const parts=await new Promise(resolve=>sabr.parseResponse(body,resolve,u=>{next=u;},{returnPartLengths:true}));
  const output=[];
  for(const [key,data] of Object.entries(parts)){
   const t=parts.contentLengths&&parts.contentLengths[key];
   if(Buffer.isBuffer(data)&&/^140-\d+$/.test(key)&&t&&Number.isFinite(t.s)&&t.ts>0&&t.l>0){
    output.push({start:t.s,duration:t.l/t.ts,data:sabr.writeNewDuration(data,t.l,t.ts).toString('base64')});
   }
  }
  if(output.length){await new Promise(resolve=>process.stdout.write(JSON.stringify({parts:output}),resolve));return;}
  if(next){url=next;continue;}
  throw Error('audio_empty');
 }
 throw Error('redirect_limit');
}
main().then(()=>process.exit(0)).catch(e=>{process.stdout.write(JSON.stringify({error:'upload_audio_unavailable',stage,httpStatus,attempts,reason:/^(audio_format|source_host|source_status|audio_empty|redirect_limit)$/.test(e.message)?e.message:e.name}));process.exit(1);});
