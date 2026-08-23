function ilpOverview(){ return document.querySelector(".ilp-overview"); }
function ilpItems(){ return [].slice.call(document.querySelectorAll(".ilp-overview .ilp-item[data-fund-id]")); }
function ilpHasOverview(){ return !!ilpOverview(); }
function ilpFundsReady(){ return ilpItems().length > 0; }
function ilpPublishHref(href){
  var h=String(href||"");
  var m=h.match(/\/en\/products\/wealth\/ilp\/[^"'#?]+/);
  if (m) return m[0].replace(/\.html$/i, "/");
  var m2=h.match(/\/pacs(\/en\/products\/wealth\/ilp\/[^"'#?]+)/);
  if (m2) return m2[1].replace(/\.html$/i, "/");
  return h;
}
function ilpState(){
  var items=ilpItems().map(function(row){
    var a=row.querySelector(".ilp-fund-name");
    return {
      id:row.getAttribute("data-fund-id")||"",
      name:a ? String(a.textContent||"").replace(/\s+/g," ").trim() : "",
      href:ilpPublishHref(a ? (a.getAttribute("href")||"") : "")
    };
  });
  var tags=[].map.call(document.querySelectorAll(".ilp-selected-tag .ilp-tag-name, .ilp-selected-tag"), function(t){
    return String(t.textContent||"").replace(/\s+/g," ").trim();
  }).filter(Boolean);
  var noRes=document.querySelector(".ilp-no-result");
  var noShown=false;
  if (noRes){
    var disp=window.getComputedStyle(noRes).display;
    noShown=disp!=="none" && disp!=="";
  }
  var cmpBtn=document.querySelector(".compare-selected-funds-btn");
  var detail=document.querySelector(".ilp-detail-page");
  return {
    itemCount:items.length,
    ids:items.map(function(x){ return x.id; }),
    names:items.map(function(x){ return x.name; }),
    hrefs:items.map(function(x){ return x.href; }),
    compareCount:document.querySelectorAll(".ilp-selected-tag").length,
    compareTags:tags,
    compareHref:ilpPublishHref(cmpBtn ? (cmpBtn.getAttribute("href")||"") : ""),
    compareDisabled:!!(cmpBtn && cmpBtn.classList.contains("disabled")),
    noResult:noShown,
    detailCiticode:detail ? (detail.getAttribute("data-fund-citicode")||"") : "",
    title:document.title||"",
    hasOverview:ilpHasOverview(),
    hasDetail:!!detail,
    hasComparePage:!!document.querySelector(".ilp-compare-page"),
    servletPath:(ilpOverview() && ilpOverview().getAttribute("data-servlet-path")) || ""
  };
}
function ilpTypeSearch(value){
  var input=document.querySelector(".ilp-search-input");
  if (!input) return false;
  try{ input.focus(); }catch(e){}
  var native=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,"value");
  if (native && native.set) native.set.call(input, String(value==null?"":value));
  else input.value=String(value==null?"":value);
  input.dispatchEvent(new Event("input",{bubbles:true}));
  return true;
}
function ilpToggleCompare(id){
  var btn=document.querySelector('.compare-btn[data-fund-id="'+id+'"]');
  if (!btn) return false;
  btn.click();
  return true;
}
function ilpRemoveCompare(id){
  var btn=document.querySelector('.ilp-selected-tag button[data-id="'+id+'"]');
  if (btn){ btn.click(); return true; }
  return ilpToggleCompare(id);
}
function ilpClearCompare(){
  document.querySelectorAll('.compare-btn.added').forEach(function(b){ b.click(); });
  return true;
}
function ilpClickDetail(id){
  var a=document.querySelector('.ilp-item[data-fund-id="'+id+'"] .ilp-fund-name');
  if (!a) return "";
  return ilpPublishHref(a.getAttribute("href")||"");
}
function ilpScroll(){
  var el=ilpOverview() || document.querySelector(".ilp-fund-search-nav") || document.body;
  try{ el.scrollIntoView({block:"center"}); }catch(e){}
  return true;
}
function ilpProbeStatus(){
  var path=(ilpOverview() && ilpOverview().getAttribute("data-servlet-path")) || "/content/prudential-aem-lbu/pacs/en/products/wealth/ilp/prulink-funds";
  var family=(ilpOverview() && ilpOverview().getAttribute("data-fund-family")) || "prulink";
  var reads=[
    path+".ilpfunds.json?family="+encodeURIComponent(family),
    path+".sync.json",
    "/bin/ilp/sync",
    "/bin/ilpfunds/sync"
  ];
  window.__ilpStatus=null;
  window.__ilpStatusReady=false;
  Promise.all(reads.map(function(url){
    return fetch(url, {credentials:"same-origin", method:"GET"}).then(function(res){
      return {url:url, status:res.status, ok:res.ok};
    }).catch(function(){
      return {url:url, status:0, ok:false};
    });
  })).then(function(rows){
    window.__ilpStatus={
      rows:rows,
      fundsOk:rows.some(function(r){ return /\.ilpfunds\.json/.test(r.url) && r.status===200; }),
      mutationAttempted:false,
      unsafe:rows.some(function(r){ return r.status>=500; })
    };
    window.__ilpStatusReady=true;
  }).catch(function(){
    window.__ilpStatus={rows:[], fundsOk:false, mutationAttempted:false, unsafe:true};
    window.__ilpStatusReady=true;
  });
  return true;
}
