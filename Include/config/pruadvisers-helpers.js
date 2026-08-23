function paInput(){
  return document.querySelector(".pa-search-input.profileName, .cmp-custom-html.pruadviser-search .pa-search-input, .pa-search-input");
}
function paHasSearch(){ return !!paInput(); }
function paSearchApi(){
  var el=document.querySelector("[data-pruadviser-search-api]");
  return el ? (el.getAttribute("data-pruadviser-search-api")||"") : "";
}
function paDecode(s){
  return String(s||"").replace(/&quot;/g,'"').replace(/&#34;/g,'"').replace(/&lt;/g,"<").replace(/&gt;/g,">").replace(/&amp;/g,"&");
}
function paMinChars(){
  var ta=document.querySelector(".cmp-custom-html.pruadviser-search textarea.pruadviser-json, .pruadviser-search textarea.pruadviser-json");
  if (ta){
    try{
      var j=JSON.parse(paDecode(ta.value||ta.textContent||""));
      var n=Number(j.minChars||j.minimumChars);
      if (n>0) return n;
    }catch(e){}
  }
  return 3;
}
function paType(value){
  var input=paInput();
  if (!input) return false;
  try{ input.focus(); }catch(e){}
  var native=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,"value");
  if (native && native.set) native.set.call(input, String(value==null?"":value));
  else input.value=String(value==null?"":value);
  input.dispatchEvent(new Event("input",{bubbles:true}));
  input.dispatchEvent(new Event("keyup",{bubbles:true}));
  return true;
}
function paUi(){
  var results=[].map.call(document.querySelectorAll(".pa-search-result"), function(a){
    return {
      name:(a.textContent||"").replace(/\s+/g," ").trim(),
      href:a.getAttribute("href")||""
    };
  });
  var empty=document.querySelector(".pa-search-empty");
  var countEl=document.querySelector(".pa-search-count, .resultCount");
  return {
    resultCount:results.length,
    names:results.map(function(r){ return r.name; }),
    hrefs:results.map(function(r){ return r.href; }),
    emptyVisible:!!(empty && String(empty.textContent||"").trim()),
    emptyText:empty ? String(empty.textContent||"").trim() : "",
    countText:countEl ? String(countEl.textContent||"").trim() : "",
    hasSearch:paHasSearch()
  };
}
function paIsSearchUrl(url){ return /pruadvisers\.json/i.test(String(url||"")); }
function paQueryOf(url){
  var m=String(url||"").match(/[?&]q=([^&]*)/);
  if (!m) return "";
  try{ return decodeURIComponent(m[1].replace(/\+/g," ")); }catch(e){ return m[1]; }
}
function paSummarize(body){
  var items=Array.isArray(body) ? body : (body && (body.items||body.results||body.data)) || [];
  if (!Array.isArray(items)) items=[];
  return {
    count:items.length,
    slugs:items.map(function(x){ return x && x.slug; }).filter(Boolean),
    names:items.map(function(x){
      return x && (x.name || [x.firstName, x.lastName].filter(Boolean).join(" ") || "");
    }).filter(Boolean)
  };
}
function paBadRecords(){
  return [
    {slug:"bad-missing-fields"},
    {firstName:"OnlyFirst", slug:"only-first"},
    {name:"Safe Record", firstName:"Safe", lastName:"Record", slug:"safe-record", pageUrl:"/pruadviser/nicoleue.html"}
  ];
}
function paInstallProbe(){
  if (window.__paHooked) return true;
  window.__paLog=[];
  window.__paSlowQuery="";
  window.__paSlowMs=0;
  window.__paMalformed=false;
  if (typeof window.fetch!=="function"){ window.__paHooked=true; return false; }
  var orig=window.fetch.bind(window);
  window.fetch=function(input, init){
    var url=typeof input==="string" ? input : ((input && input.url) || "");
    if (!paIsSearchUrl(url)) return orig(input, init);
    var q=paQueryOf(url);
    var delay=(window.__paSlowQuery && q===window.__paSlowQuery) ? Number(window.__paSlowMs||0) : 0;
    var mutate=!!window.__paMalformed;
    var pending=orig(input, init).then(function(res){
      if (mutate){
        window.__paMalformed=false;
        var fake=paBadRecords();
        var sum=paSummarize(fake);
        window.__paLog.push({url:url, q:q, status:200, count:sum.count, slugs:sum.slugs, names:sum.names, fake:true, at:Date.now()});
        return new Response(JSON.stringify(fake), {status:200, headers:{"Content-Type":"application/json"}});
      }
      return res.clone().json().then(function(body){
        var sum=paSummarize(body);
        window.__paLog.push({url:url, q:q, status:res.status, count:sum.count, slugs:sum.slugs, names:sum.names, fake:false, at:Date.now()});
        return res;
      }).catch(function(){
        window.__paLog.push({url:url, q:q, status:res.status, count:0, slugs:[], names:[], fake:false, at:Date.now()});
        return res;
      });
    });
    if (!delay) return pending;
    return pending.then(function(res){
      return new Promise(function(ok){ setTimeout(function(){ ok(res); }, delay); });
    });
  };
  window.__paHooked=true;
  return true;
}
function paLast(){
  var log=window.__paLog||[];
  var last=log.length ? log[log.length-1] : {};
  return {
    requests:log.length,
    queries:log.map(function(x){ return x.q; }),
    minChars:paMinChars(),
    api:paSearchApi(),
    hasSearch:paHasSearch(),
    url:last.url||"",
    q:last.q||"",
    status:last.status||0,
    count:last.count||0,
    slugs:last.slugs||[],
    names:last.names||[],
    fake:!!last.fake,
    ui:paUi()
  };
}
function paClearLog(){ window.__paLog=[]; return true; }
function paSlow(q, ms){ window.__paSlowQuery=String(q||""); window.__paSlowMs=Number(ms)||1500; return true; }
function paMalformed(){ window.__paMalformed=true; return true; }
function paScrollSearch(){
  var el=document.querySelector(".pa-search-modal, .cmp-custom-html.pruadviser-search, .pruadviser-search") || document.body;
  try{ el.scrollIntoView({block:"center"}); }catch(e){}
  return true;
}
