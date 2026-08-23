function pfHasForm(){
  if (document.getElementById("pruReport")) return true;
  if (document.querySelector(".oh-cf, .oh-cf-form-details, [class*='get-in-touch'], [class*='getintouch']")) return true;
  var t=(document.body && document.body.innerText)||"";
  return /get in touch|download your report|first name/i.test(t);
}
function pfDecode(s){
  return String(s||"").replace(/&quot;/g,'"').replace(/&#34;/g,'"').replace(/&lt;/g,"<").replace(/&gt;/g,">").replace(/&amp;/g,"&");
}
function pfParseJson(raw){
  try{ return JSON.parse(pfDecode(raw)); }catch(e){ return null; }
}
function pfWalk(obj, acc){
  acc=acc||{};
  if (!obj || typeof obj!=="object") return acc;
  if (Array.isArray(obj)){ obj.forEach(function(x){ pfWalk(x, acc); }); return acc; }
  Object.keys(obj).forEach(function(k){
    var v=obj[k];
    var lk=k.toLowerCase();
    if (lk==="payloadtype" && v) acc.payloadType=String(v);
    if ((lk==="endpoint" || lk==="submitendpoint") && v) acc.leadEndpoint=acc.leadEndpoint||String(v);
    if (lk==="recaptchasitekey" || (lk==="sitekey" && String(v).length>10)) acc.recaptchaSiteKey=acc.recaptchaSiteKey||String(v);
    if (lk==="enabled" && typeof v==="boolean") acc.emailEnabled=v;
    if (lk==="template" || lk==="templatename") acc.templatePresent=true;
    if (lk==="to" || lk==="recipient" || lk==="cc") acc.recipientPresent=true;
    if (v && typeof v==="object") pfWalk(v, acc);
  });
  return acc;
}
function pfPageJson(){
  var acc={};
  document.querySelectorAll("textarea, script[type='application/json'], .oh-cf-form-details, .custom-html-settings textarea").forEach(function(el){
    var j=pfParseJson(el.value||el.textContent||"");
    if (j) pfWalk(j, acc);
  });
  return acc;
}
function pfInlineBlob(){
  var parts=[];
  document.querySelectorAll("script").forEach(function(s){
    if (s.textContent) parts.push(s.textContent);
  });
  return parts.join("\n");
}
function pfClientlibSrcs(){
  var srcs=[];
  document.querySelectorAll("script[src]").forEach(function(s){
    var src=s.src||"";
    if (/discoveryreport|pruadviser|articles-page|customhtmlpacs/i.test(src)) srcs.push(src);
  });
  return srcs;
}
function pfClientlibHint(){
  var joined=pfClientlibSrcs().join("\n");
  return {
    discovery:/discoveryreport/i.test(joined),
    pruadviser:/pruadviser/i.test(joined),
    opus:/articles-page/i.test(joined)
  };
}
function pfMask(s){
  return String(s||"").replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, "[email]");
}
function pfTrustedHost(url){
  var u=String(url||"");
  if (!/^https:\/\//i.test(u)) return false;
  return /\.prudential\.com\.sg(\/|$)/i.test(u);
}
function pfSameOrigin(url){
  var u=String(url||"");
  if (!u) return false;
  if (u.indexOf("/")===0 && u.indexOf("//")!==0) return true;
  try{
    var a=document.createElement("a");
    a.href=u;
    return a.host===location.host;
  }catch(e){ return false; }
}
function pfInspectSync(kind, extraBlob){
  var json=pfPageJson();
  var hint=pfClientlibHint();
  var blob=pfInlineBlob()+"\n"+(extraBlob||"");
  var report=document.getElementById("pruReport");
  var dataEndpoint=report ? (report.getAttribute("data-submit-endpoint")||"") : "";
  var dataKey=report ? (report.getAttribute("data-captcha-key")||"") : "";
  var widget=document.querySelector("[data-sitekey], .g-recaptcha, #gCaptchaId");
  var widgetKey=widget ? (widget.getAttribute("data-sitekey")||"") : "";
  var recaptchaKey=dataKey || json.recaptchaSiteKey || widgetKey || "";
  var endpoint=dataEndpoint;
  var source="";
  var payloadMode="";
  var method="POST";
  if (kind==="discovery"){
    if (!endpoint){
      var hit=blob.match(/["']([^"']*leadSubmit\.json)["']/i);
      if (hit) endpoint=hit[1];
    }
    source=/X-PACS-Form-Source["']?\s*[:=]\s*["']discovery-report["']/.test(blob) || hint.discovery ? "discovery-report" : "";
    payloadMode=/application\/x-www-form-urlencoded/i.test(blob) || hint.discovery ? "urlencoded" : "";
  } else if (kind==="pruadviser"){
    if (/pacsleadsubmit\.pruadviser\.json/i.test(blob) || hint.pruadviser) endpoint="/content/api/pacsleadsubmit.pruadviser.json";
    source=/X-PACS-Form-Source["']?\s*[:=]\s*["']pruadviser["']/.test(blob) || hint.pruadviser ? "pruadviser" : "";
    payloadMode=json.payloadType || (/payloadType\s*\|\|\s*['"]json['"]/.test(blob) ? "json" : "");
    if (json.payloadType) payloadMode=json.payloadType;
  } else if (kind==="opus"){
    if (/pacsleadsubmit\.opus\.json/i.test(blob) || hint.opus) endpoint="/content/api/pacsleadsubmit.opus.json";
    source=/X-PACS-Form-Source["']?\s*[:=]\s*["']opus["']/.test(blob) || hint.opus ? "opus" : "";
    payloadMode=json.payloadType || "form";
  }
  var csrfScript=!!document.querySelector("script[src*='granite/csrf']");
  var csrfHeader=/CSRF-Token/.test(blob);
  var csrfGranite=/Granite\.csrf|granite\/csrf\/token/.test(blob) || csrfScript;
  if (!csrfHeader && csrfScript && (hint.discovery || hint.pruadviser || hint.opus || /X-PACS-Form-Source/.test(blob))) csrfHeader=true;
  var lead=json.leadEndpoint||"";
  var recaptchaConfigured=!!(dataKey || json.recaptchaSiteKey || widget);
  return {
    kind:kind,
    pageUrl:location.href,
    hasForm:pfHasForm(),
    endpoint:pfMask(endpoint),
    endpointSameOrigin:pfSameOrigin(endpoint),
    payloadMode:payloadMode,
    method:method,
    csrfScript:csrfScript,
    csrfHeader:csrfHeader,
    csrfGranite:csrfGranite,
    sourceHeader:source,
    recaptchaSiteKey:recaptchaKey ? (String(recaptchaKey).slice(0,8)+"…") : "",
    recaptchaPresent:!!recaptchaKey,
    recaptchaConfigured:recaptchaConfigured,
    trustedLeadHttps:pfTrustedHost(lead),
    trustedLeadPresent:!!lead,
    emailEnabled:json.emailEnabled===true,
    templatePresent:!!json.templatePresent,
    recipientPresent:!!json.recipientPresent,
    submitted:false
  };
}
function pfStartInspect(kind){
  window.__pfInspect=null;
  window.__pfReady=false;
  var srcs=pfClientlibSrcs();
  var finish=function(extra){
    window.__pfInspect=pfInspectSync(kind, extra||"");
    window.__pfReady=true;
  };
  if (!srcs.length){ finish(""); return true; }
  if (typeof fetch!=="function"){ finish(""); return true; }
  Promise.all(srcs.map(function(src){
    return fetch(src, {credentials:"same-origin"}).then(function(r){ return r.ok ? r.text() : ""; }).catch(function(){ return ""; });
  })).then(function(texts){
    finish(texts.join("\n"));
  }).catch(function(){ finish(""); });
  return true;
}
function pfScrollForm(){
  var el=document.querySelector("#pruReport, form, .oh-cf, .get-in-touch, [class*='contact']") || document.body;
  try{ el.scrollIntoView({block:"center"}); }catch(e){}
  return true;
}
