'use strict';
// Six-second, multiplexed MPEG-TS VOD for Android 4.0. No full-video download.
const fs=require('fs'), path=require('path'), cp=require('child_process');
// A separate cache prevents mixing previous 480p pieces with the mobile profile.
const STEP=6, ROOT=path.join(__dirname,'../galaxy-stream-cache-mobile');
const sessions=new Map();
const metrics={profile:'360p-400k',requests:0,segments:0,errors:0,last:null};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function error(s){return new Error(s);}
function run(args){return new Promise((resolve,reject)=>{
  const p=cp.spawn('ffmpeg',args,{stdio:['ignore','ignore','pipe']});
  let tail='';p.stderr.on('data',b=>{tail=(tail+b.toString()).slice(-1600);});
  const timer=setTimeout(()=>p.kill('SIGKILL'),30000);
  p.on('error',e=>{clearTimeout(timer);reject(e);});
  p.on('close',code=>{clearTimeout(timer);code===0?resolve():reject(error('Media conversion failed '+code));});
});}
function sabr(){return require('./yt2009sabr');}
class Stream {
  constructor(id){
    this.id=id;this.pid=sabr().initPlaybackSession(id,['360p','240p','144p']).split('pid=')[1];
    this.dir=path.join(ROOT,id);fs.mkdirSync(this.dir,{recursive:true});
    this.parts=new Map();this.pending=new Map();this.queue=Promise.resolve();this.last=Date.now();this.duration=0;
    this.ready=this.enqueue(()=>this.fetch(0));
    this.ready.catch(()=>{if(sessions.get(id)===this)sessions.delete(id);});
  }
  enqueue(fn){const p=this.queue.then(fn);this.queue=p.catch(()=>{});return p;}
  async fetch(offset){
    const start=Date.now();
    const data=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(error('Video source timed out')),25000);
      sabr().handlePlayer(this.pid,Math.max(0,Math.floor(offset*1000)),{headers:{},query:{hd:1,return_video_length:1,return_part_lengths:1}},d=>{clearTimeout(timer);d?resolve(d):reject(error('Video source unavailable'));});
    });
    const duration=Number(data.videoLength);
    if(Number.isFinite(duration)&&duration>0&&duration<=43200)this.duration=duration;
    for(const [key,b] of Object.entries(data)){
      if(!Buffer.isBuffer(b)||!/^\d+-\d+$/.test(key))continue;
      const t=data.contentLengths&&data.contentLengths[key];
      if(!t||!Number.isFinite(t.s)||!(t.ts>0)||!(t.l>0))continue;
      const type=/^(139|140)-/.test(key)?'a':'v';
      this.parts.set(key,{key,type,start:t.s,end:t.s+t.l/t.ts,data:sabr().writeNewDuration(b,t.l,t.ts)});
    }
    metrics.lastFetchMs=Date.now()-start;
    if(!this.duration)throw error('Video duration unavailable');
  }
  coverage(type,start,end){
    let cursor=start;const list=[];
    for(const p of [...this.parts.values()].filter(p=>p.type===type&&p.end>start+0.001&&p.start<end-0.001).sort((a,b)=>a.start-b.start)){
      if(p.start>cursor+0.06)break;
      if(p.end>cursor){list.push(p);cursor=p.end;}
    }
    return {list,cursor,ok:cursor>=end-0.06};
  }
  async prepare(n){
    const target=path.join(this.dir,n+'.ts');
    this.last=Date.now();
    if(fs.existsSync(target)){fs.utimesSync(target,new Date(),new Date());return target;}
    if(this.pending.has(n))return this.pending.get(n);
    const promise=this.enqueue(async()=>{
      const began=Date.now();await this.ready;
      const start=n*STEP;let end=Math.min(start+STEP,this.duration);
      if(!(n>=0&&Number.isInteger(n)&&start<this.duration))throw error('Segment out of range');
      for(const [key,p] of this.parts)if(p.end<start-12||p.start>end+18)this.parts.delete(key);
      let video,audio;
      for(let tries=0;tries<7;tries++){
        video=this.coverage('v',start,end);audio=this.coverage('a',start,end);
        if(video.ok&&audio.ok)break;
        // lengthSeconds is rounded up. The final audio/video sample endpoints
        // can both end slightly earlier; use their common playable interval.
        const tail=Math.min(video.cursor,audio.cursor);
        if(end===this.duration&&tail>start&&tail>=end-1&&Math.abs(video.cursor-audio.cursor)<0.15){
          end=tail;this.duration=tail;video=this.coverage('v',start,end);audio=this.coverage('a',start,end);break;
        }
        // SABR can return no media when seeking into its final partial chunk.
        // Request from the preceding chunk to include the actual final samples.
        await this.fetch(Math.min(Math.min(video.cursor,audio.cursor)+0.15,Math.max(0,this.duration-STEP-0.25)));
        if(tries>2)await sleep(100);
      }
      video=this.coverage('v',start,end);audio=this.coverage('a',start,end);
      if(!video.ok||!audio.ok)throw error('Incomplete source interval');
      const tmp=fs.mkdtempSync(path.join(this.dir,'part-'));
      try{
        const lists={};
        for(const [type,parts] of [['v',video.list],['a',audio.list]]){
          lists[type]=path.join(tmp,type+'.txt');
          let lines=[];
          for(let i=0;i<parts.length;i++){
            const p=parts[i],name=type+i+'.mp4';fs.writeFileSync(path.join(tmp,name),p.data);
            lines.push("file '"+name+"'",'duration '+(p.end-p.start).toFixed(9));
          }
          fs.writeFileSync(lists[type],lines.join('\n')+'\n');
        }
        const out=path.join(tmp,'out.ts');
        await run(['-hide_banner','-loglevel','error','-y',
          '-f','concat','-safe','1','-i',lists.v,
          '-f','concat','-safe','1','-i',lists.a,
          '-filter_complex',`[0:v]trim=start=${(start-video.list[0].start).toFixed(6)}:duration=${(end-start).toFixed(6)},setpts=PTS-STARTPTS,scale=-2:'min(360,ih)',fps=24,format=yuv420p[v];[1:a]atrim=start=${(start-audio.list[0].start).toFixed(6)}:duration=${(end-start).toFixed(6)},asetpts=PTS-STARTPTS[a]`,
          '-map','[v]','-map','[a]','-t',(end-start).toFixed(6),
          '-c:v','libx264','-preset','veryfast','-crf','26',
          '-profile:v','baseline','-level','3.1','-maxrate','400k','-bufsize','400k','-threads','2','-g','48','-sc_threshold','0',
          '-c:a','aac','-b:a','96k','-ac','2','-ar','44100',
          '-output_ts_offset',(start+1).toFixed(6),'-avoid_negative_ts','disabled','-muxdelay','0','-muxpreload','0','-f','mpegts',out]);
        if(fs.statSync(out).size<188)throw error('Empty segment');
        fs.renameSync(out,target);
        metrics.segments++;metrics.last={id:this.id,segment:n,readyMs:Date.now()-began,bytes:fs.statSync(target).size};
        fs.writeFileSync(path.join(ROOT,'status.json'),JSON.stringify(metrics));
        return target;
      }finally{fs.rmSync(tmp,{recursive:true,force:true});}
    });
    this.pending.set(n,promise);
    promise.then(()=>this.pending.delete(n),()=>this.pending.delete(n));
    return promise;
  }
  manifest(){
    const lines=['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-TARGETDURATION:6','#EXT-X-MEDIA-SEQUENCE:0','#EXT-X-PLAYLIST-TYPE:VOD'];
    for(let n=0;n*STEP<this.duration;n++)lines.push('#EXTINF:'+Math.min(STEP,this.duration-n*STEP).toFixed(3)+',',`http://127.0.0.1:8768/sabrhls-video-fragment/galaxy/${this.id}/${n}.ts`);
    lines.push('#EXT-X-ENDLIST');return lines.join('\n')+'\n';
  }
}
function get(id){
 if(!/^[a-zA-Z0-9_-]{11}$/.test(id))throw error('Invalid video ID');
 let s=sessions.get(id);
 if(!s){
  if(sessions.size>=3){
   const idle=[...sessions.values()].filter(v=>!v.pending.size).sort((a,b)=>a.last-b.last)[0];
   if(!idle)throw error('Video worker busy');
   sessions.delete(idle.id);idle.parts.clear();
  }
  s=new Stream(id);sessions.set(id,s);
 }
 s.last=Date.now();return s;
}
function fail(res){metrics.errors++;if(!res.headersSent)res.status(502).send('Video preparation failed');else res.end();}
exports.register=app=>{
 fs.mkdirSync(ROOT,{recursive:true});
 app.get('/sabr_hls_adapter/galaxy/:id/index.m3u8',async(req,res)=>{
  try{const s=get(req.params.id);await s.ready;await s.prepare(0);if(s.duration>STEP)await s.prepare(1);res.set('Cache-Control','no-store').type('application/vnd.apple.mpegurl').send(s.manifest());}catch(e){fail(res);}
 });
 app.get('/sabrhls-video-fragment/galaxy/:id/:part',async(req,res)=>{
  try{
   if(!/^\d{1,5}\.ts$/.test(req.params.part))return res.sendStatus(404);
   const s=get(req.params.id),n=Number(req.params.part.slice(0,-3));await s.ready;metrics.requests++;
   const file=await s.prepare(n);res.type('video/mp2t').sendFile(file);
   // Keep twelve seconds ahead; seeking requests another numbered piece.
   for(let ahead=1;ahead<=2;ahead++)if((n+ahead)*STEP<s.duration)s.prepare(n+ahead).catch(()=>{});
  }catch(e){fail(res);}
 });
 // Periodic 128 MiB cache target; no media or request bodies enter diagnostics.
 setInterval(()=>{
  try{
   for(const [id,s] of sessions)if(Date.now()-s.last>15*60*1000&&!s.pending.size)sessions.delete(id);
   let files=[];for(const entry of fs.readdirSync(ROOT,{withFileTypes:true})){
    if(!entry.isDirectory()||!/^[a-zA-Z0-9_-]{11}$/.test(entry.name))continue;
    const dir=path.join(ROOT,entry.name);for(const name of fs.readdirSync(dir))if(/^\d+\.ts$/.test(name)){const p=path.join(dir,name),st=fs.statSync(p);files.push({p,size:st.size,time:st.mtimeMs});}
   }
   let bytes=files.reduce((s,f)=>s+f.size,0);for(const f of files.sort((a,b)=>a.time-b.time))if(bytes>128*1024*1024||Date.now()-f.time>60*60*1000){fs.unlinkSync(f.p);bytes-=f.size;}
  }catch(e){}
 },60000).unref();
};
