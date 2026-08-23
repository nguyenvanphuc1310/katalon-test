function ppcRoot(){ return document.querySelector("[data-pacs-healthcare-provider]"); }
function ppcHasSearch(){ return !!ppcRoot() && !!document.querySelector("search-filter-atom"); }
function ppcReady(){
  if (!ppcHasSearch()) return false;
  if (document.querySelector(".doc-hosp__cards-skeleton")) return false;
  var no=document.querySelector("[data-pacs-no-results], .doc-hosp__no-results");
  var noShown=!!(no && !no.classList.contains("doc-hosp__no-results--hidden"));
  return ppcCards().length>0 || noShown;
}
function ppcDismiss(){
  [".first-load-popup__close","[data-first-load-popup-close]",".popup-learmore.show .close-popup","#onetrust-accept-btn-handler","#truste-consent-button"].forEach(function(sel){
    var el=document.querySelector(sel);
    if (el) el.click();
  });
  document.querySelectorAll(".popup-learmore.show").forEach(function(p){ p.classList.remove("show"); });
  document.querySelectorAll("[data-first-load-popup]").forEach(function(p){ p.hidden=true; p.classList.remove("show","is-open"); });
  return true;
}
function ppcCards(){
  return [].slice.call(document.querySelectorAll(".doc-hosp__card:not(.skeleton-card)"));
}
function ppcState(){
  var cards=ppcCards().map(function(card){
    var title=card.querySelector(".doc-hosp__card-title");
    var tels=[].map.call(card.querySelectorAll('a[href^="tel"]'), function(a){ return a.getAttribute("href")||""; });
    var apps=[].map.call(card.querySelectorAll("a.doc-hosp__profile-appointment, a[href*='ppc-validate']"), function(a){ return a.getAttribute("href")||""; });
    var info=card.querySelector(".doc-hosp__information, .doc-hosp__subtitle, .doc-hosp__profile-detail");
    return {
      title:title ? String(title.textContent||"").replace(/\s+/g," ").trim() : "",
      tels:tels,
      appointments:apps,
      hasDetails:!!(info && String(info.textContent||"").trim())
    };
  });
  var no=document.querySelector("[data-pacs-no-results], .doc-hosp__no-results");
  var noShown=!!(no && !no.classList.contains("doc-hosp__no-results--hidden"));
  var countEl=document.querySelector(".doc-hosp__result-count");
  return {
    hasSearch:ppcHasSearch(),
    cardCount:cards.length,
    titles:cards.map(function(c){ return c.title; }),
    details:cards.filter(function(c){ return c.hasDetails; }).length,
    telOk:cards.some(function(c){ return c.tels.some(function(h){ return /^tel:/i.test(h); }); }),
    appointmentOk:cards.some(function(c){ return c.appointments.some(function(h){ return /ppc-validate/i.test(h); }); }),
    noResult:noShown,
    countText:countEl ? String(countEl.textContent||"").trim() : "",
    uncaught:!!window.__ppcUncaught
  };
}
function ppcSearch(opts){
  opts=opts||{};
  var atom=document.querySelector("search-filter-atom");
  if (!atom) return false;
  var keyword=String(opts.keyword||"");
  var primary=String(opts.primary||"");
  var detail={
    data:{
      input:keyword,
      keyword:keyword,
      dropdown1:primary ? [primary] : [],
      dropdown2:[],
      primary:primary,
      secondary:""
    },
    input:keyword,
    keyword:keyword
  };
  atom.dispatchEvent(new CustomEvent("search-filter-submit",{bubbles:true, detail:detail}));
  return true;
}
function ppcInstallProbe(){
  window.__ppcLog=window.__ppcLog||[];
  window.__ppcUncaught=false;
  window.addEventListener("error", function(){ window.__ppcUncaught=true; });
  window.addEventListener("unhandledrejection", function(){ window.__ppcUncaught=true; });
  if (window.__ppcHooked || typeof window.fetch!=="function") return true;
  var orig=window.fetch.bind(window);
  window.fetch=function(input, init){
    var url=typeof input==="string" ? input : ((input && input.url) || "");
    if (!/pacs-healthcare-provider\.json/i.test(url)) return orig(input, init);
    if (window.__ppcForceError){
      window.__ppcForceError=false;
      window.__ppcLog.push({url:url, status:500, error:true});
      return Promise.resolve(new Response("{}", {status:500, headers:{"Content-Type":"application/json"}}));
    }
    return orig(input, init).then(function(res){
      window.__ppcLog.push({url:url, status:res.status, error:false, q:url});
      return res;
    });
  };
  window.__ppcHooked=true;
  return true;
}
function ppcForceError(){ window.__ppcForceError=true; return true; }
function ppcClearLog(){ window.__ppcLog=[]; return true; }
function ppcRevealDetails(){
  var card=ppcCards()[0];
  if (!card) return false;
  var loc=(card.getAttribute("data-option-primary")||"").split(",")[0].trim();
  if (!loc) loc="Mount Alvernia Hospital";
  var atom=card.querySelector("dropdown-atom");
  if (atom) atom.dispatchEvent(new CustomEvent("dropdown-change",{bubbles:true, detail:{value:loc, label:loc}}));
  var btn=card.querySelector(".doc-hosp__accordion-button");
  if (btn) btn.click();
  return true;
}
function ppcLast(){
  var log=window.__ppcLog||[];
  var last=log.length ? log[log.length-1] : {};
  return Object.assign({requests:log.length, status:last.status||0, error:!!last.error}, ppcState());
}
function ppcScroll(){
  var el=ppcRoot() || document.body;
  try{ el.scrollIntoView({block:"center"}); }catch(e){}
  return true;
}
