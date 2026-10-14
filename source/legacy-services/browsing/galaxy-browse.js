"use strict";
// Public browsing feeds for the 2012 client. No Google login, cookies, or invented rankings.
const fs=require('fs'), path=require('path');
const ROOT=path.resolve(__dirname,'../galaxy-browse-cache');
const sources={music:'/channel/UC-9-kyTW8ZkZNDHQJ6FgpwQ',sports:'/channel/UCEgdi0XIXXZ-qJOFPf4JSKw',gaming:'/gaming',news:'/feed/news_destination'};
const topics={Film:'movie trailers',Autos:'cars automobiles',Comedy:'comedy',Entertainment:'entertainment',Howto:'how to DIY',People:'vlogs',Pets:'animals pets',Animals:'animals pets',Education:'educational science',Tech:'science technology',Travel:'travel destinations',Nonprofit:'nonprofit activism'};
const pending=new Map();
function text(v){if(typeof v==='string')return v;return v?.simpleText||v?.content||(v?.runs||[]).map(x=>x.text||'').join('');}
function walk(v,fn){if(!v||typeof v!=='object')return;fn(v);for(const x of Object.values(v))if(x&&typeof x==='object')walk(x,fn);}
function seconds(t){if(!/^\d+(?::\d{2}){1,2}$/.test(t||''))return 0;return t.split(':').reduce((a,b)=>a*60+Number(b),0);}
function first(v,pick){let result;walk(v,x=>{if(result===undefined){const r=pick(x);if(r!==undefined)result=r;}});return result;}
function count(t){const exact=(t||'').match(/\b([\d,.]+)\s*([KMB]?) views?\b/i);if(exact)return Math.round(Number(exact[1].replace(/,/g,''))*({K:1000,M:1000000,B:1000000000}[exact[2].toUpperCase()]||1));return null;}
function published(t){const m=(t||'').match(/\b(\d+)\s*(second|minute|hour|day|week|month|year|[smhdwy])s? ago\b/i);if(!m)return '';const units={second:1,s:1,minute:60,m:60,hour:3600,h:3600,day:86400,d:86400,week:604800,w:604800,month:2592000,year:31536000,y:31536000};return new Date(Date.now()-Number(m[1])*units[m[2].toLowerCase()]*1000).toISOString();}
function parseInitial(html){const m=/(?:var\s+)?ytInitialData\s*=\s*/.exec(html);if(!m)throw Error('Browse data unavailable');let start=m.index+m[0].length,depth=0,quote=false,escape=false;for(let i=start;i<html.length;i++){let c=html[i];if(quote){if(escape)escape=false;else if(c==='\\')escape=true;else if(c==='"')quote=false;}else{if(c==='"')quote=true;else if(c==='{')depth++;else if(c==='}'&&--depth===0)return JSON.parse(html.slice(start,i+1));}}throw Error('Incomplete browse data');}
function normalize(v,shelf,kind){
 const model=kind==='lockupViewModel', meta=model?v.metadata?.lockupMetadataViewModel:null;
 const id=model?v.contentId:v.videoId;if(!/^[\w-]{11}$/.test(id||'')||(model&&v.contentType!=='LOCKUP_CONTENT_TYPE_VIDEO'))return null;
 const title=text(model?meta?.title:v.title);if(!title)return null;
 let duration=model?first(v.contentImage,x=>{let t=text(x.thumbnailBadgeViewModel?.text);return seconds(t)?seconds(t):undefined;}):seconds(text(v.lengthText));
 if(!duration)duration=first(v.thumbnailOverlays,x=>{let t=text(x.thumbnailOverlayTimeStatusRenderer?.text);return seconds(t)?seconds(t):undefined;})||0;
 if(!duration)return null; // Live streams and Shorts use playback paths this client has not qualified.
 const byline=v.ownerText||v.shortBylineText||v.longBylineText||v.bylineText;
 let author=model?text(meta?.metadata?.contentMetadataViewModel?.metadataRows?.[0]?.metadataParts?.[0]?.text):text(byline);
 let channel=first(model?meta:byline,x=>/^UC[\w-]{22}$/.test(x.browseEndpoint?.browseId||'')?x.browseEndpoint.browseId:undefined)||'';
 const views=model?first(meta,x=>{const n=count(text(x.text));return n===null?undefined:n;}):count(text(v.viewCountText));
 const date=model?first(meta,x=>published(text(x.text))||undefined):published(text(v.publishedTimeText));
 return {id,title,author:author||'YouTube',channel,duration,views:views??null,published:date||'',shelf,description:text(v.descriptionSnippet)};
}
function extract(data){let videos=[],playlists=[];function visit(v,shelf=''){if(!v||typeof v!=='object')return;
  if(v.shelfRenderer){const s=v.shelfRenderer;visit(s.content,text(s.title));return;}
  if(v.richShelfRenderer){const s=v.richShelfRenderer;visit(s.contents,text(s.title));return;}
  for(const kind of ['videoRenderer','gridVideoRenderer','compactVideoRenderer','videoCardRenderer','playlistVideoRenderer','lockupViewModel'])if(v[kind]){let n=normalize(v[kind],shelf,kind);if(n)videos.push(n);if(kind==='lockupViewModel'){let x=v[kind];if(/^[\w-]{12,100}$/.test(x.contentId||'')&&x.contentType!=='LOCKUP_CONTENT_TYPE_VIDEO')playlists.push({id:x.contentId,title:text(x.metadata?.lockupMetadataViewModel?.title),shelf});}return;}
  for(const x of Object.values(v))visit(x,shelf);
 }visit(data.contents||data);return {videos:unique(videos),playlists};}
function unique(a){const ids=new Set();return a.filter(x=>!ids.has(x.id)&&ids.add(x.id));}
async function page(route){const url=new URL(route,'https://www.youtube.com');if(url.hostname!=='www.youtube.com')throw Error('Source');url.searchParams.set('hl','en');url.searchParams.set('gl','US');const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),18000);try{const r=await fetch(url,{headers:{'User-Agent':'Mozilla/5.0','Accept-Language':'en-US,en;q=0.9'},signal:controller.signal});if(!r.ok)throw Error('Public source '+r.status);let parts=[],size=0;for await(const b of r.body){size+=b.length;if(size>6000000)throw Error('Source too large');parts.push(b);}return extract(parseInitial(Buffer.concat(parts).toString('utf8')));}finally{clearTimeout(timer);}}
function prune(){try{const files=fs.readdirSync(ROOT).filter(n=>/^(music|sports|gaming|news|music-chart|category-[A-Za-z]+|related-[\w-]{11})\.json$/.test(n)).map(n=>({name:n,time:fs.statSync(path.join(ROOT,n)).mtimeMs})).sort((a,b)=>b.time-a.time);for(let i=0;i<files.length;i++)if(i>=96||Date.now()-files[i].time>12*3600000)fs.unlinkSync(path.join(ROOT,files[i].name));}catch{}}
async function cache(key,make){fs.mkdirSync(ROOT,{recursive:true});const file=path.join(ROOT,key+'.json');let old;try{old=JSON.parse(fs.readFileSync(file,'utf8'));if(old.version===2&&Date.now()-old.time<15*60000)return old.data;}catch{}if(pending.has(key))return pending.get(key);const p=make().then(data=>{fs.writeFileSync(file,JSON.stringify({version:2,time:Date.now(),data}));prune();return data;}).catch(e=>{if(old&&Date.now()-old.time<12*3600000)return old.data;throw e;}).finally(()=>pending.delete(key));pending.set(key,p);return p;}
function explore(name){if(!sources[name])throw Error('Category');return cache(name,()=>page(sources[name]));}
async function musicChart(){return cache('music-chart',async()=>{const music=await explore('music');const chart=music.playlists.find(p=>/^Trending \d+ United States$/i.test(p.title));if(!chart)throw Error('Music chart unavailable');const result=await page('/playlist?list='+encodeURIComponent(chart.id));if(!result.videos.length)throw Error('Music chart empty');return result.videos;});}
async function trending(){const parts=await Promise.allSettled([musicChart(),explore('sports').then(x=>x.videos.filter(v=>/^Trending$/i.test(v.shelf)))]);const good=parts.filter(x=>x.status==='fulfilled').map(x=>x.value);if(!good.some(a=>a.length))throw Error('Trending unavailable');return weave(good);}
function weave(lists){let out=[];for(let i=0;i<100;i++)for(const a of lists)if(a[i])out.push(a[i]);return unique(out).slice(0,100);}
async function popular(){const r=await Promise.allSettled(['gaming','sports','music','news'].map(n=>explore(n)));const a=r.filter(x=>x.status==='fulfilled').map(x=>x.value.videos);if(!a.some(x=>x.length))throw Error('Explore unavailable');return weave(a);}
async function category(name){const map={Music:'music',Sports:'sports',Games:'gaming',Gaming:'gaming',News:'news'};if(map[name])return (await explore(map[name])).videos;const q=topics[name];if(!q)throw Error('Unsupported category');return (await cache('category-'+name,()=>page('/results?search_query='+encodeURIComponent(q)))).videos;}
function xml(s){return String(s??'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&apos;');}
function render(videos,req){const t=require('./yt2009templates');let start=Math.max(1,Math.min(101,Number(req.query['start-index'])||1)),max=Math.max(1,Math.min(50,Number(req.query['max-results'])||25));let batch=videos.slice(start-1,start-1+max),h=t.gdata_feedStart.replace(/<openSearch:totalResults>[^<]+/,`<openSearch:totalResults>${videos.length}`).replace(/<openSearch:startIndex>[^<]+/,`<openSearch:startIndex>${start}`).replace(/<openSearch:itemsPerPage>[^<]+/,`<openSearch:itemsPerPage>${batch.length}`);
 if(start-1+max<videos.length){let q=new URLSearchParams(req.query);q.set('start-index',String(start+max));q.set('max-results',String(max));h+=`<link rel="next" href="${xml('http://127.0.0.1:8768'+req.path+'?'+q)}"/>`;}
 for(const v of batch){let e=t.gdata_feedVideo(v.id,v.title,xml(v.author),v.views||0,v.duration,v.description,v.published||'','','',[],['480p'],{authorId:v.channel,omitY9:true});e=e.replace(/<gd:rating\b[^>]*\/>/g,'').replace(/<yt:rating\b[^>]*\/>/g,'').replace(/ favoriteCount="[^"]*"/g,'').replace(/ countHint='[^']*'/g,'').replace(/<(published|updated|yt:uploaded)><\/\1>/g,'');if(v.views===null)e=e.replace(/<yt:statistics\b[^>]*\/>/g,'');h+=e;}return h+t.gdata_feedEnd;
}
function register(app){app.get('/feeds/api/standardfeeds/*',async(req,res)=>{try{const endpoint=req.path.split('/').pop();let m=/^(?:most_popular|most_viewed|top_rated|top_favorites|most_discussed)_(\w+)$/.exec(endpoint);const videos=m?await category(m[1]):['most_discussed','on_the_web'].includes(endpoint)?await trending():await popular();res.set('Content-Type','application/atom+xml').set('Cache-Control','private, max-age=120').send(render(videos,req));}catch(e){res.status(503).set('Retry-After','60').send('Public feed temporarily unavailable');}});}
async function related(id){if(!/^[\w-]{11}$/.test(id))throw Error('Video ID');return cache('related-'+id,async()=>{let r=await page('/watch?v='+id);return r.videos.filter(v=>v.id!==id).slice(0,50);});}
const registerFeeds=register;
module.exports={register(app){registerFeeds(app);app.get('/feeds/api/videos/:id/related',async(req,res)=>{try{const videos=await related(req.params.id);res.set('Content-Type','application/atom+xml').send(render(videos,req));}catch(e){res.status(503).set('Retry-After','60').send('Related videos temporarily unavailable');}});},parseInitial,extract,normalize,render,trending,popular,category,related};
