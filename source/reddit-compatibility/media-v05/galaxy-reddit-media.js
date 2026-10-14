'use strict';
// Public Reddit clips are converted on the Pixel; no account, cookies or API access.
const fs=require('fs'),path=require('path'),https=require('https'),cp=require('child_process');
const ROOT=path.join(__dirname,'../galaxy-reddit-media-cache');
const PREFIX='/sabrhls-video-fragment/reddit/';
const pending=new Map(),failures=new Map();let active=0,blockedUntil=0;
function record(event){try{fs.mkdirSync(ROOT,{recursive:true});const f=path.join(ROOT,'events.jsonl');if(fs.existsSync(f)&&fs.statSync(f).size>32768)fs.writeFileSync(f,'');fs.appendFileSync(f,JSON.stringify(Object.assign({at:Date.now()},event))+'\n');}catch(_) {}}
function failure(status,message){return Object.assign(new Error(message),{status});}
function validId(id){return /^[a-z0-9]{6,32}$/.test(id);}
function source(id,file){
 if(!validId(id)||!/^[A-Za-z0-9_-]+\.(m3u8|mp4)$/.test(file))throw failure(400,'Invalid video source');
 return 'https://v.redd.it/'+id+'/'+file;
}
function blocked(){return Date.now()<blockedUntil;}
function fetch(id,file,max){
 if(blocked())return Promise.reject(failure(429,'Reddit asked to wait. Try later.'));
 const url=source(id,file);
 return new Promise((resolve,reject)=>{
  const kind=file==='HLSPlaylist.m3u8'?'master':file.endsWith('.m3u8')?'track-playlist':'track-media';
  const req=https.get(url,{headers:{'User-Agent':'GalaxyLegacyReader/0.4 (personal compatibility)'}},res=>{
   record({kind,status:res.statusCode});
   if(res.statusCode!==200){
    if(res.statusCode===403||res.statusCode===429){
     const seconds=Number(res.headers['retry-after']);blockedUntil=Date.now()+Math.max(600,Math.min(86400,Number.isFinite(seconds)?seconds:600))*1000;
     fs.mkdirSync(ROOT,{recursive:true});fs.writeFileSync(path.join(ROOT,'backoff.json'),JSON.stringify({until:blockedUntil,status:res.statusCode,kind}));
    }
    res.resume();reject(failure(res.statusCode===404?404:res.statusCode===403?403:res.statusCode===429?429:502,'Reddit video unavailable'));return;
   }
   let bytes=0;const parts=[];
   res.on('data',b=>{bytes+=b.length;if(bytes>max){req.destroy(failure(413,'Video is too large'));return;}parts.push(b);});
   res.on('error',reject);res.on('end',()=>resolve(Buffer.concat(parts)));
  });
  const timer=setTimeout(()=>req.destroy(failure(504,'Video source timed out')),20000);
  req.on('close',()=>clearTimeout(timer));req.on('error',reject);
 });
}
function attributes(line){const out={};for(const m of line.matchAll(/([A-Z-]+)=("[^"]*"|[^,]*)/g))out[m[1]]=m[2].replace(/^"|"$/g,'');return out;}
function select(master){
 const lines=master.split(/\r?\n/),audio=new Map(),variants=[];
 for(let i=0;i<lines.length;i++){
  if(lines[i].startsWith('#EXT-X-MEDIA:')){const a=attributes(lines[i]);if(a.TYPE==='AUDIO')audio.set(a['GROUP-ID'],a.URI);}
  if(lines[i].startsWith('#EXT-X-STREAM-INF:')){const a=attributes(lines[i]),size=(a.RESOLUTION||'').split('x').map(Number);if(!/\bavc1\./.test(a.CODECS||'')||size.length!==2||!size.every(n=>n>0&&n<=4096))continue;variants.push({file:lines[i+1],audio:a.AUDIO,short:Math.min(...size),area:size[0]*size[1]});}
 }
 const v=variants.filter(v=>v.short<=480).sort((a,b)=>b.area-a.area)[0]||variants.sort((a,b)=>a.area-b.area)[0];
 if(!v)throw failure(422,'Video format unavailable');
 return {video:v.file,audio:v.audio?audio.get(v.audio):null};
}
function media(playlist){
 if(!playlist.startsWith('#EXTM3U')||!playlist.includes('#EXT-X-ENDLIST')||playlist.includes('#EXT-X-KEY:'))throw failure(422,'Unsupported video playlist');
 let duration=0;for(const m of playlist.matchAll(/^#EXTINF:([0-9.]+),/gm))duration+=Number(m[1]);
 if(!(duration>0&&duration<=600))throw failure(413,'This clip exceeds the ten-minute limit');
 const files=playlist.split(/\r?\n/).filter(l=>l&&!l.startsWith('#'));
 const unique=[...new Set(files)];
 if(unique.length!==1||!/^[A-Za-z0-9_-]+\.mp4$/.test(unique[0]))throw failure(422,'Unsupported video fragments');
 const map=/^#EXT-X-MAP:(.*)$/m.exec(playlist);if(!map||attributes(map[1]).URI!==unique[0])throw failure(422,'Unsupported video initialization');
 return {file:unique[0],duration};
}
function probe(file){return new Promise((resolve,reject)=>{
 const p=cp.spawn('ffprobe',['-v','error','-show_streams','-of','json',file],{stdio:['ignore','pipe','ignore']});let data='';
 const timer=setTimeout(()=>p.kill('SIGKILL'),5000);p.stdout.on('data',b=>{data+=b.toString();if(data.length>65536)p.kill('SIGKILL');});
 p.on('error',e=>{clearTimeout(timer);reject(e);});p.on('close',code=>{clearTimeout(timer);try{if(code!==0)throw Error('probe');resolve(JSON.parse(data).streams);}catch(_){reject(failure(422,'Video properties unavailable'));}});
 });}
function compatibleVideo(v){
 if(!v)return false;const rate=String(v.avg_frame_rate).split('/').map(Number),fps=rate.length===2?rate[0]/rate[1]:rate[0];
 return v.codec_name==='h264'&&['Constrained Baseline','Baseline','Main','High'].includes(v.profile)&&v.pix_fmt==='yuv420p'&&v.width>0&&v.width<=854&&v.height>0&&v.height<=854&&v.level>0&&v.level<=31&&fps>0&&fps<=30.1;
}
function compatibleAudio(a){return !a||a.codec_name==='aac'&&a.profile==='LC'&&a.channels>0&&a.channels<=2&&Number(a.sample_rate)>0&&Number(a.sample_rate)<=48000;}
async function convert(video,audio,out){
 const videoStreams=await probe(video),audioStreams=audio?await probe(audio):videoStreams;
 const copy=compatibleVideo(videoStreams.find(s=>s.codec_type==='video'))&&compatibleAudio(audioStreams.find(s=>s.codec_type==='audio'));
 await new Promise((resolve,reject)=>{
 const args=['-hide_banner','-loglevel','error','-y','-i',video];
 if(audio)args.push('-i',audio);
 args.push('-map','0:v:0');if(audio)args.push('-map','1:a:0');else args.push('-map','0:a:0?');
 if(copy)args.push('-c:v','copy','-c:a','copy');
 else args.push('-vf',"scale='min(480,iw)':'min(800,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,fps=24,format=yuv420p",'-c:v','libx264','-preset','veryfast','-crf','24','-profile:v','baseline','-level','3.1','-maxrate','900k','-bufsize','900k','-threads','2','-c:a','aac','-b:a','96k','-ac','2','-ar','44100');
 args.push('-movflags','+faststart',out);
 const p=cp.spawn('ffmpeg',args,{stdio:['ignore','ignore','ignore']});const timer=setTimeout(()=>p.kill('SIGKILL'),50000);
 p.on('error',e=>{clearTimeout(timer);reject(e);});p.on('close',code=>{clearTimeout(timer);code===0?resolve():reject(failure(502,'Video conversion failed'));});
 });return copy?'remux':'transcode';}
async function prepare(id){
 if(!validId(id))throw failure(400,'Invalid video ID');
 fs.mkdirSync(ROOT,{recursive:true});const target=path.join(ROOT,id+'.mp4');
 if(fs.existsSync(target)){fs.utimesSync(target,new Date(),new Date());return target;}
 if(pending.has(id))return pending.get(id);
 if(blocked())throw failure(429,'Reddit asked to wait. Try later.');
 if(failures.has(id)){const e=failures.get(id);throw failure(e.status,e.message);}
 if(active)throw failure(503,'Another video is loading. Try again.');
 active++;
 const job=(async()=>{
  const began=Date.now(),dir=fs.mkdtempSync(path.join(ROOT,'work-'));
  try{
   const selected=select((await fetch(id,'HLSPlaylist.m3u8',65536)).toString('utf8'));
   const video=media((await fetch(id,selected.video,65536)).toString('utf8'));
   const audio=selected.audio?media((await fetch(id,selected.audio,65536)).toString('utf8')):null;
   fs.writeFileSync(path.join(dir,'video.mp4'),await fetch(id,video.file,48*1024*1024));
   if(audio)fs.writeFileSync(path.join(dir,'audio.mp4'),await fetch(id,audio.file,12*1024*1024));
   const mode=await convert(path.join(dir,'video.mp4'),audio?path.join(dir,'audio.mp4'):null,path.join(dir,'out.mp4'));
   fs.renameSync(path.join(dir,'out.mp4'),target);
   fs.writeFileSync(path.join(ROOT,'status.json'),JSON.stringify({readyMs:Date.now()-began,seconds:video.duration,bytes:fs.statSync(target).size,audio:!!audio,mode}));
   return target;
  }catch(e){failures.set(id,{status:e.status||502,message:e.message,at:Date.now()});record({kind:'preparation',status:e.status||502});throw e;}
  finally{active--;pending.delete(id);fs.rmSync(dir,{recursive:true,force:true});}
 })();pending.set(id,job);return job;
}
function player(id){if(!validId(id))throw failure(400,'Invalid video ID');return '<!doctype html><html><head><title>Reddit video</title><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;background:#111;color:#ccc;font:16px sans-serif}video{width:100%}</style></head><body><video controls autoplay preload="auto" src="'+id+'.mp4"></video></body></html>';}
function messagePage(id,title,message,refresh){
 if(!validId(id))throw failure(400,'Invalid video ID');
 const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
 return '<!doctype html><html><head><title>Reddit video</title><meta name="viewport" content="width=device-width,initial-scale=1">'+(refresh?'<meta http-equiv="refresh" content="2">':'')+'<style>body{margin:24px;background:#111;color:#ddd;font:17px sans-serif}h2{font-size:20px}a{color:#9acbff}</style></head><body><h2>'+esc(title)+'</h2><p>'+esc(message)+'</p>'+(refresh?'':'<p><a href="'+id+'.html?retry=1">Try again</a></p>')+'</body></html>';
}
function errorPage(id,e,remaining){
 if(remaining>0)return messagePage(id,'Please wait','Reddit video requests are paused. Try again in '+Math.max(1,Math.ceil(remaining/60000))+' minute(s).',false);
 const titles={403:'Video access denied',404:'Video unavailable',413:'Video is too large',422:'Video format not supported',429:'Please try later',503:'Another video is loading',504:'Video preparation timed out'};
 const message=e.status===503?'Go back to the current video, or try this one again shortly.':e.status===403?'Reddit did not allow this video request.':e.status===422?'This video uses a format the companion cannot convert yet.':e.status===413?'This clip exceeds the companion\'s duration or size limit.':'The Pixel could not prepare this video. You can try again.';
 return messagePage(id,titles[e.status]||'Video unavailable',message,false);
}
function pageState(id){
 const target=path.join(ROOT,id+'.mp4');
 if(fs.existsSync(target))return {status:200,body:player(id)};
 if(blocked())return {status:429,body:errorPage(id,{status:429},blockedUntil-Date.now())};
 if(pending.has(id))return {status:202,body:messagePage(id,'Preparing video','The Pixel is getting the picture and sound ready.',true)};
 const failed=failures.get(id);
 if(failed)return {status:failed.status,body:errorPage(id,failed,0)};
 if(active)return {status:503,body:errorPage(id,{status:503},0)};
 prepare(id).catch(()=>{});
 return {status:202,body:messagePage(id,'Preparing video','The Pixel is getting the picture and sound ready.',true)};
}
exports.register=app=>{
 fs.mkdirSync(ROOT,{recursive:true});try{blockedUntil=JSON.parse(fs.readFileSync(path.join(ROOT,'backoff.json'),'utf8')).until||0;}catch(_){}
 app.get(PREFIX+':file',async(req,res)=>{
  const m=/^([a-z0-9]{6,32})\.(html|mp4)$/.exec(req.params.file);if(!m)return res.sendStatus(404);
  res.set('Cache-Control',m[2]==='html'?'no-store':'private, max-age=900');
  try{if(m[2]==='html'){if(req.query.retry==='1'){if(!blocked())failures.delete(m[1]);return res.redirect(303,m[1]+'.html');}const page=pageState(m[1]);return res.status(page.status).type('html').send(page.body);}const file=await prepare(m[1]);res.type('video/mp4').sendFile(file);}catch(e){if(!res.headersSent)res.status(e.status||502).type('text').send(e.message||'Video unavailable');}
 });
 setInterval(()=>{try{let files=fs.readdirSync(ROOT).filter(n=>/^[a-z0-9]{6,32}\.mp4$/.test(n)).map(n=>({p:path.join(ROOT,n),s:fs.statSync(path.join(ROOT,n))}));let bytes=files.reduce((n,f)=>n+f.s.size,0);for(const f of files.sort((a,b)=>a.s.mtimeMs-b.s.mtimeMs))if(bytes>128*1024*1024||Date.now()-f.s.mtimeMs>3600000){fs.unlinkSync(f.p);bytes-=f.s.size;}}catch(_){}},60000).unref();
};
exports.test={validId,source,select,media,player,messagePage,errorPage,compatibleVideo,compatibleAudio};
