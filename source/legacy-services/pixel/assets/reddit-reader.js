(function () {
  const attr = (e, n) => e.getAttribute(n) || '';
  const link = s => { try { const u = new URL(s, location.href); return /^https?:$/.test(u.protocol) && !u.username && !u.password ? u.href : ''; } catch (_) { return ''; } };
  const escape = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  function html(e) {
    if (!e) return '';
    if (e.nodeType === 3) return escape(e.textContent);
    if (e.nodeType !== 1) return '';
    const t = e.tagName.toLowerCase();
    if (/^(script|style|iframe|object|embed|template|svg|button|input)$/.test(t)) return '';
    if (t === 'img') return escape(attr(e, 'alt'));
    const inside = Array.from(e.childNodes).map(html).join('');
    if (t === 'a') { const u = link(attr(e, 'href')); return u ? '<a href="' + escape(u) + '">' + inside + '</a>' : inside; }
    if (/^(p|b|strong|em|i|u|s|del|blockquote|pre|code|ol|ul|li|h[1-6])$/.test(t)) return '<' + t + '>' + inside + '</' + t + '>';
    if (t === 'br' || t === 'hr') return '<' + t + '>';
    return inside;
  }
  const posts = Array.from(document.querySelectorAll('shreddit-post')).filter(e => /^t3_[a-z0-9]+$/.test(e.id) && !e.hasAttribute('is-ad')).slice(0, 100).map(e => {
    const id = e.id, body = e.querySelector('[id="' + id + '-post-rtjson-content"]') || e.querySelector('[slot="text-body"]');
    const img = e.querySelector('[slot="post-media-container"] img,shreddit-aspect-ratio img');
    return {id:id,title:attr(e,'post-title'),author:attr(e,'author'),subreddit:attr(e,'subreddit-name'),permalink:attr(e,'permalink'),url:link(attr(e,'content-href')||attr(e,'permalink')),created:Date.parse(attr(e,'created-timestamp'))/1000||0,score:attr(e,'score'),score_hidden:e.hasAttribute('is-score-hidden')||!e.hasAttribute('score'),num_comments:attr(e,'comment-count'),type:attr(e,'post-type'),html:html(body).slice(0,50000),thumbnail:img?link(attr(img,'src')):'',over_18:e.hasAttribute('is-nsfw')};
  });
  if (!posts.length) document.querySelectorAll('search-telemetry-tracker[data-testid="search-sdui-post"]').forEach(e => {
    const a=e.querySelector('[data-testid="post-title-text"]')||e.querySelector('[data-testid="post-title"]');
    const id=attr(e,'data-thingid');if(!a||!/^t3_[a-z0-9]+$/.test(id)||posts.some(p=>p.id===id)||posts.length>=100)return;
    const permalink=attr(a,'href'),sub=/^\/r\/([^/]+)\/comments\//.exec(permalink);
    if(!sub)return;
    const counters=e.querySelectorAll('[data-testid="search-counter-row"] faceplate-number'),time=e.querySelector('time');
    const image=Array.from(e.querySelectorAll('img,faceplate-img')).find(i=>!/^https:\/\/styles\./.test(attr(i,'src')));
    posts.push({id:id,title:(a.textContent||attr(a,'aria-label')).trim(),author:'',subreddit:sub[1],permalink:permalink,url:link(permalink),created:time?Date.parse(attr(time,'datetime'))/1000||0:0,score:counters[0]?attr(counters[0],'number'):'',score_hidden:!counters[0],num_comments:counters[1]?attr(counters[1],'number'):'',type:'discussion',html:'',thumbnail:image?link(attr(image,'src')):''});
  });
  const comments = Array.from(document.querySelectorAll('shreddit-comment')).slice(0, 150).map(e => {
    const parent = e.parentElement && e.parentElement.closest('shreddit-comment');
    const body = e.querySelector('[slot="comment"]'), action = e.querySelector('shreddit-comment-action-row');
    return {id:attr(e,'thingid'),author:attr(e,'author'),parent:parent?attr(parent,'thingid'):attr(e,'postid'),permalink:attr(e,'permalink'),created:Date.parse(attr(e,'created'))/1000||0,html:html(body).slice(0,30000),score:attr(action||e,'score'),score_hidden:!action||action.hasAttribute('is-score-hidden')};
  }).filter(c => /^t1_[a-z0-9]+$/.test(c.id));
  const text=(document.body?document.body.innerText||'':'').slice(0,20000).toLowerCase();
  const blocked=/blocked by network security|prove you.re human|verify you are human|unusual traffic|enter the characters|whoa there, pardner/.test(text);
  const unavailable=/this community is private|this community has been banned|this community is not available|mature content|you must be 18/.test(text);
  const empty=/hasn.t posted yet|there are no posts|no results found|no posts yet|be the first to comment/.test(text);
  return JSON.stringify({title:document.title,posts:posts,comments:comments,blocked:blocked,unavailable:unavailable,empty:empty,ready:document.readyState!=='loading'});
})()
