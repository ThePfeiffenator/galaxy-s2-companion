// Local FFmpeg regression: actual audio adapter, synthetic media, no network.
'use strict';
const fs=require('fs'),path=require('path'),os=require('os'),vm=require('vm'),cp=require('child_process'),assert=require('assert');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'galaxy-audio-test-')),back=path.join(root,'back');fs.mkdirSync(back);
function ff(args){cp.execFileSync('ffmpeg',['-hide_banner','-loglevel','error','-y',...args],{stdio:['ignore','ignore','pipe']});}
function probe(file){return JSON.parse(cp.execFileSync('ffprobe',['-v','error','-select_streams','a:0','-show_packets','-show_entries','packet=pts_time,duration_time','-of','json',file],{encoding:'utf8'})).packets;}
async function test(){
 ff(['-f','lavfi','-i','sine=frequency=997:sample_rate=44100:duration=37','-c:a','aac','-b:a','128k','-f','segment','-segment_time','10','-reset_timestamps','1',path.join(root,'source-%d.m4a')]);
 const fixture={contentLengths:{}};let position=0;
 for(let i=0;fs.existsSync(path.join(root,`source-${i}.m4a`));i++){
  const file=path.join(root,`source-${i}.m4a`),packets=probe(file),length=packets.reduce((a,p)=>a+Number(p.duration_time),0),key=`140-${i+1}`;
  fixture[key]=fs.readFileSync(file);fixture.contentLengths[key]={s:position,l:Math.round(length*44100),ts:44100};position+=length;
 }
 fixture.videoLength=position;
 const sabr={initPlaybackSession:()=>'?pid=fixture',handlePlayer:(id,offset,request,callback)=>callback(fixture),writeNewDuration:x=>x};
 const context={require:n=>n==='./yt2009sabr'?sabr:require(n),__dirname:back,exports:{},module:{exports:{}},setTimeout,clearTimeout,setInterval,Buffer,console};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../youtube-google/galaxy-audio.js'),'utf8')+'\nmodule.exports.Stream=Stream;',context);
 const stream=new context.module.exports.Stream('Synthetic01');await stream.ready;const records=new Map();
 // Source-chunk boundaries do not align with output joins. Exercise seeks,
 // cache reuse, the final partial piece, and invalid requests as well.
 for(const n of [0,1,5,2,3,4,5,6]){
  const packets=probe(await stream.prepare(n)),last=packets.at(-1);
  const result={n,first:Number(packets[0].pts_time),end:Number(last.pts_time)+Number(last.duration_time)};
  if(records.has(n))assert.deepStrictEqual(records.get(n),result);records.set(n,result);
 }
 const ordered=[...records.values()].sort((a,b)=>a.n-b.n);let maximum=0;
 for(let i=1;i<ordered.length;i++){
  const gap=ordered[i].first-ordered[i-1].end;maximum=Math.max(maximum,Math.abs(gap));
  assert(Math.abs(gap)<0.002,`Audible timeline gap/overlap at join ${i}: ${gap}`);
 }
 for(const n of [-1,1.5,999])await assert.rejects(stream.prepare(n));
 console.log(`PASS audio-only joins, seeking, cache and tail; maximum rounding ${(maximum*1000).toFixed(3)} ms. No phone listening test.`);
}
test().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>{
 assert(path.dirname(root)===path.resolve(os.tmpdir())&&path.basename(root).startsWith('galaxy-audio-test-'));
 fs.rmSync(root,{recursive:true,force:true});
});
