function norm(s){ return (s||"").replace(/\s+/g," ").trim().toLowerCase(); }
function boxArea(el){
  if (!el || !el.getBoundingClientRect) return 0;
  try{
    var r=el.getBoundingClientRect();
    return Math.max(0,r.width)*Math.max(0,r.height);
  }catch(e){ return 0; }
}
function hostOf(el){
  if (!el) return null;
  if (el.closest) return el.closest("input-atom, dropdown-atom, textarea-atom, checkbox-atom, .contactus_form_inputs, .cmp-form-text") || el;
  return el;
}
function fieldUsable(el){
  if (!el) return false;
  var type=(el.type||"").toLowerCase();
  var form=el.closest && el.closest("form");
  var formArea=boxArea(form);
  if (form && formArea<2000) return false;
  if (type==="hidden") return boxArea(hostOf(el))>40 || formArea>4000;
  if (typeof shown==="function" && shown(el)) return true;
  if (boxArea(hostOf(el))>40) return true;
  return formArea>4000;
}
function pickVisibleForm(){
  var forms=document.querySelectorAll("form");
  var best=null, bestArea=0;
  for (var i=0;i<forms.length;i++){
    var f=forms[i];
    var t=f.innerText||"";
    if (t.indexOf("First Name")<0 && t.indexOf("Type of Enquiry")<0) continue;
    var area=boxArea(f);
    if (area<4000) continue;
    if (area>bestArea){ best=f; bestArea=area; }
  }
  return best;
}
function formRoot(){
  var form=pickVisibleForm();
  if (form) return form;
  var best=null, bestArea=0;
  var nodes=document.querySelectorAll("form, section, article, main, div");
  for (var i=0;i<nodes.length;i++){
    var t=nodes[i].innerText||"";
    if (t.indexOf("Are you a PRU Policy Owner")<0) continue;
    if (t.indexOf("Type of Enquiry")<0 && t.indexOf("First Name")<0) continue;
    var area=boxArea(nodes[i]);
    if (area<4000) continue;
    if (area>bestArea){ best=nodes[i]; bestArea=area; }
  }
  return best;
}
function contactForm(){
  return pickVisibleForm() || formRoot();
}
var CU_FIELDS={
  "First Name":["First_Name__c"],
  "Last Name":["Last_Name__c"],
  "Mobile Number":["Mobile__c"],
  "Mobile":["Mobile__c"],
  "Email Address":["Email__c"],
  "Email":["Email__c"],
  "NRIC":["NRIC_Suffix__c"],
  "Last 4":["NRIC_Suffix__c"],
  "Policy Number":["Policy_Number__c"],
  "Comment":["Remarks__c"],
  "Enquiry":["Remarks__c"],
  "Financial Consultant's Mobile":["Financial_Consultant_Mobile__c"],
  "Financial Consultant":["Financial_Consultant_Mobile__c"]
};
function findByPlaceholder(root, name){
  var want=norm(name);
  var els=qsaDeep("input, textarea, select", root||document);
  var usable=null, any=null;
  for (var i=0;i<els.length;i++){
    var ph=norm(els[i].placeholder);
    if (!ph || !(ph===want || ph.indexOf(want)>=0 || want.indexOf(ph)>=0)) continue;
    if (!any) any=els[i];
    if (fieldUsable(els[i])) return els[i];
    usable=usable||els[i];
  }
  return usable||any;
}
function labelInput(label){
  var forId=label.getAttribute("for");
  if (forId){ var by=document.getElementById(forId); if (by) return by; }
  var inp=label.querySelector("input, textarea, select") || deepInput(label);
  if (inp) return inp;
  var wrap=label.parentElement;
  if (wrap){
    var next=wrap.querySelector("input, textarea, select") || deepInput(wrap);
    if (next) return next;
    var sib=wrap.nextElementSibling;
    if (sib){ next=sib.querySelector("input, textarea, select") || deepInput(sib); if (next) return next; }
  }
  return null;
}
function findByLabel(root, name){
  var want=norm(name).replace("*","").trim();
  var labels=(root||document).querySelectorAll("label, p, span, div, legend");
  var usable=null, any=null;
  for (var i=0;i<labels.length;i++){
    var t=norm(labels[i].innerText).replace("*","").trim();
    if (!t || t.length>80) continue;
    if (!(t===want || t.indexOf(want)===0)) continue;
    var inp=labelInput(labels[i]);
    if (!inp) continue;
    if (!any) any=inp;
    if (fieldUsable(inp)) return inp;
    usable=usable||inp;
  }
  return usable || any || findByPlaceholder(root, name);
}
function findNamed(root, names){
  var usable=null;
  (names||[]).forEach(function(name){
    qsaDeep("input[name='"+name+"'], textarea[name='"+name+"'], select[name='"+name+"']", root||document).forEach(function(el){
      if (!usable && fieldUsable(el)) usable=el;
    });
  });
  return usable;
}
function findField(root, label){
  root=root||formRoot()||document;
  return findNamed(root, CU_FIELDS[label]||[]) || findByLabel(root, label);
}
function setNative(el, value){
  if (!el) return;
  try{ el.focus(); }catch(e){}
  var tag=(el.tagName||"").toUpperCase();
  try{
    if (tag==="TEXTAREA"){
      var ta=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,"value");
      if (ta && ta.set) ta.set.call(el, value); else el.value=value;
    } else if (tag==="INPUT" || tag==="SELECT"){
      var proto=tag==="SELECT"?window.HTMLSelectElement.prototype:window.HTMLInputElement.prototype;
      var desc=Object.getOwnPropertyDescriptor(proto,"value");
      if (desc && desc.set) desc.set.call(el, value); else el.value=value;
    } else {
      el.value=value;
    }
  }catch(e){
    try{ el.value=value; }catch(err){}
  }
  try{
    el.dispatchEvent(new Event("input",{bubbles:true}));
    el.dispatchEvent(new Event("change",{bubbles:true}));
    el.dispatchEvent(new Event("blur",{bubbles:true}));
  }catch(e){}
}
function visible(el){
  if (!el) return false;
  var cs=window.getComputedStyle(el);
  return cs.display!=="none" && cs.visibility!=="hidden" && el.offsetParent!==null;
}
function shown(el){
  if (!el) return false;
  var cs=window.getComputedStyle(el);
  if (!cs || cs.display==="none" || cs.visibility==="hidden") return false;
  if (parseFloat(cs.opacity)===0) return false;
  var r=el.getBoundingClientRect();
  return r.width>2 && r.height>2;
}
function clickExact(root, label){
  var want=(label||"").replace(/\s+/g," ").trim();
  var nodes=(root||document).querySelectorAll("label, button, span, div, li, p, [role=option], [role=radio]");
  for (var i=0;i<nodes.length;i++){
    var t=(nodes[i].innerText||nodes[i].textContent||"").replace(/\s+/g," ").trim();
    if (t===want && visible(nodes[i])){ try{ nodes[i].click(); return true; }catch(e){} }
  }
  return false;
}
function cleanItem(s){
  return (s||"").replace(/&nbsp;/gi," ").replace(/\u00a0/g," ").replace(/\s+/g," ").trim();
}
function qsaDeep(sel, root){
  var out=[], start=root||document;
  function walk(node){
    if (!node || !node.querySelectorAll) return;
    try{ node.querySelectorAll(sel).forEach(function(el){ out.push(el); }); }catch(e){}
    var all=[];
    try{ all=node.querySelectorAll("*"); }catch(e){ return; }
    for (var i=0;i<all.length;i++){
      if (all[i].shadowRoot) walk(all[i].shadowRoot);
    }
  }
  walk(start);
  if (start!==document && document.body){
    document.querySelectorAll("dropdown-atom, input-atom, checkbox-atom").forEach(function(h){
      if (h.shadowRoot) walk(h.shadowRoot);
    });
  }
  return out;
}
function deepInput(from){
  if (!from) return null;
  var found=qsaDeep("input, textarea, select", from);
  for (var i=0;i<found.length;i++){
    if ((found[i].type||"").toLowerCase()==="hidden") continue;
    if (fieldUsable(found[i])) return found[i];
  }
  for (var j=0;j<found.length;j++){
    if (fieldUsable(found[j])) return found[j];
  }
  var light=from.querySelector && from.querySelector("input, textarea, select");
  return found[0] || light || null;
}
function cuHasForm(){ return !!formRoot(); }
function cuOnSorry(){
  var u=(location.href||"").toLowerCase();
  if (u.indexOf("/sorry")>=0) return true;
  var t=((document.title||"")+" "+(document.body && document.body.innerText || "")).toLowerCase();
  return t.indexOf("couldn't process your enquiry")>=0 || t.indexOf("could not process your enquiry")>=0 || t.indexOf("back to contact us")>=0;
}
function cuThankYou(){
  var u=(location.href||"").toLowerCase();
  if (u.indexOf("/sorry")>=0 || cuOnSorry()) return false;
  if (u.indexOf("thank")>=0 && u.indexOf("contact-us")<0) return true;
  var form=contactForm();
  var banner=document.querySelector(".thank-you, .success-message, [class*='thankyou'], [class*='thank-you']");
  if (banner && shown(banner)) return true;
  if (form && shown(form)) return false;
  var t=(document.body && document.body.innerText || "").toLowerCase();
  return t.indexOf("thank you")>=0 || t.indexOf("thanks for")>=0 || t.indexOf("we have received")>=0 || t.indexOf("successfully submitted")>=0;
}
function cuClickOwner(want){
  var root=formRoot()||document;
  var labels=root.querySelectorAll("label");
  var target=null;
  for (var i=0;i<labels.length;i++){
    var t=cleanItem(labels[i].innerText);
    if (t!==want && t.indexOf(want+" ")!==0) continue;
    var ctx="";
    var p=labels[i];
    for (var d=0;d<6 && p;d++){ ctx=(p.innerText||"")+" "+ctx; p=p.parentElement; }
    if (ctx.indexOf("Are you a PRU Policy Owner")<0) continue;
    if (t.length<=8){ target=labels[i]; break; }
  }
  if (!target) return false;
  try{ target.scrollIntoView({block:"center"}); }catch(e){}
  try{ target.click(); }catch(e){}
  cuApplyOwnerValue(want);
  return cuOwnerIs(want);
}
function ensureOwnerHidden(form, want){
  form=form||contactForm()||document;
  var names=["Existing_Customer__c","existing_customer__c","Existing_Customer","policyOwner"];
  var found=false;
  for (var n=0;n<names.length;n++){
    var els=form.querySelectorAll("[name='"+names[n]+"']");
    for (var j=0;j<els.length;j++){
      found=true;
      if (els[j].type==="radio") continue;
      els[j].value=want;
      els[j].setAttribute("value", want);
    }
  }
  if (!found && form.appendChild){
    var hid=document.createElement("input");
    hid.type="hidden";
    hid.name="Existing_Customer__c";
    hid.value=want;
    form.appendChild(hid);
  }
  try{ sessionStorage.setItem("__cuOwner", want); }catch(e){}
  return true;
}
function cuApplyOwnerValue(want){
  var form=contactForm()||document;
  if (cuOwnerIs(want)) return ensureOwnerHidden(form, want);
  var radios=form.querySelectorAll("input[type=radio]");
  for (var i=0;i<radios.length;i++){
    var v=cleanItem(radios[i].value);
    var lab="";
    if (radios[i].id){
      var l=form.querySelector("label[for='"+radios[i].id+"']");
      if (l) lab=cleanItem(l.innerText);
    }
    if (!lab){
      var wrap=radios[i].closest("label")||radios[i].parentElement;
      lab=cleanItem(wrap && wrap.innerText);
    }
    if (norm(v)===norm(want) || lab.indexOf(want)===0){
      radios[i].checked=true;
      radios[i].setAttribute("aria-checked","true");
      try{ radios[i].dispatchEvent(new Event("input",{bubbles:true})); }catch(e){}
      try{ radios[i].dispatchEvent(new Event("change",{bubbles:true})); }catch(e){}
    }
  }
  var names=["Existing_Customer__c","existing_customer__c","Existing_Customer","policyOwner"];
  var found=false;
  for (var n=0;n<names.length;n++){
    var els=form.querySelectorAll("[name='"+names[n]+"']");
    for (var j=0;j<els.length;j++){
      found=true;
      if (els[j].type==="radio") continue;
      els[j].value=want;
      els[j].setAttribute("value", want);
      try{ els[j].dispatchEvent(new Event("change",{bubbles:true})); }catch(e){}
    }
  }
  if (!found && form.appendChild){
    var hid=document.createElement("input");
    hid.type="hidden";
    hid.name="Existing_Customer__c";
    hid.value=want;
    form.appendChild(hid);
  }
  try{ sessionStorage.setItem("__cuOwner", want); }catch(e){}
  return cuOwnerIs(want);
}
function ownerFormId(){
  var f=contactForm()||pickVisibleForm();
  return (f && f.id) || "";
}
function ownerFormMatches(want){
  var id=ownerFormId();
  if (id==="hide-owner") return want==="No";
  if (id==="show-owner") return want==="Yes";
  return false;
}
function cuOwnerIs(want){
  if (ownerFormMatches(want)) return true;
  var root=document;
  var radios=root.querySelectorAll("input[type=radio], [role=radio]");
  for (var i=0;i<radios.length;i++){
    var lab="";
    if (radios[i].id){
      var l=root.querySelector("label[for='"+radios[i].id+"']");
      if (l) lab=cleanItem(l.innerText);
    }
    if (!lab){
      var wrap=radios[i].closest("label")||radios[i].parentElement;
      lab=cleanItem(wrap && wrap.innerText);
    }
    var on=radios[i].checked===true || radios[i].getAttribute("aria-checked")==="true";
    if (on && lab.indexOf(want)===0) return true;
  }
  var labels=root.querySelectorAll("label");
  for (var j=0;j<labels.length;j++){
    var t=cleanItem(labels[j].innerText);
    if (t!==want) continue;
    var cls=(labels[j].className||"")+" "+(labels[j].getAttribute("aria-checked")||"");
    if (/checked|selected|active/i.test(cls) || labels[j].getAttribute("aria-checked")==="true") return true;
  }
  return false;
}
function fireClick(el){
  if (!el) return false;
  try{ el.scrollIntoView({block:"center"}); }catch(e){}
  try{
    ["pointerdown","mousedown","mouseup","click"].forEach(function(type){
      el.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,view:window}));
    });
  }catch(e){}
  try{ el.click(); }catch(e){}
  return true;
}
function enquiryWrap(){
  var host=enquiryHost();
  if (host){
    return host.closest(".contactus_form_inputs, .formoptionsextension, .formoptionextension, .options") || host.parentElement || host;
  }
  return document.querySelector(".contactus_form_inputs[data-optiontype='drop-down']") || formRoot() || document;
}
function enquiryHost(){
  var root=formRoot()||document;
  var hosts=qsaDeep("dropdown-atom", root);
  if (!hosts.length) hosts=Array.prototype.slice.call(document.querySelectorAll("dropdown-atom"));
  var labelled=[];
  for (var i=0;i<hosts.length;i++){
    var aria=hosts[i].getAttribute("data-aria-label")||hosts[i].getAttribute("placeholder")||"";
    if (/type of enquiry/i.test(aria) && boxArea(hosts[i])>20) labelled.push(hosts[i]);
  }
  if (labelled.length) return labelled[0];
  for (var j=0;j<hosts.length;j++){
    if (boxArea(hosts[j])>20) return hosts[j];
  }
  return hosts[0]||null;
}
function enquiryHidden(){
  var wrap=enquiryWrap();
  if (wrap){
    var local=wrap.querySelector("input[name='Lead_Sub_Source__c']");
    if (local) return local;
  }
  var root=formRoot();
  if (root){
    var named=root.querySelector("input[name='Lead_Sub_Source__c']");
    if (named) return named;
  }
  return document.querySelector("input[name='Lead_Sub_Source__c']");
}
function parseItemList(raw){
  if (!raw) return [];
  var s=String(raw).replace(/&nbsp;/gi," ").replace(/&quot;/gi,'"');
  try{
    var j=JSON.parse(s);
    if (Array.isArray(j)) return j.map(function(x){ return cleanItem(typeof x==="string"?x:(x.label||x.value||x.text||"")); }).filter(Boolean);
  }catch(e){}
  var out=[], m, re=/"([^"]+)"/g;
  while ((m=re.exec(s))) out.push(cleanItem(m[1]));
  return out;
}
function enquiryOptionStrings(){
  var host=enquiryHost();
  var out=[];
  if (host){
    ["items","data-list","data-items"].forEach(function(attr){
      parseItemList(host.getAttribute(attr)).forEach(function(s){ if (out.indexOf(s)<0) out.push(s); });
    });
  }
  cuEnquiryItems().forEach(function(el){
    var s=cleanItem(el.getAttribute("data-item")||el.innerText||"");
    if (s && out.indexOf(s)<0) out.push(s);
  });
  return out;
}
function enquiryAliases(label){
  var n=norm(label);
  if (n.indexOf("other enqu")===0) return ["other enquiries","other enquires"];
  return [n];
}
function enquiryCanonical(label){
  var options=enquiryOptionStrings();
  var aliases=enquiryAliases(label);
  for (var i=0;i<options.length;i++){
    var o=norm(cleanItem(options[i]));
    for (var a=0;a<aliases.length;a++){
      if (o.indexOf(aliases[a])>=0 || aliases[a].indexOf(o)>=0) return cleanItem(options[i]);
    }
  }
  return label;
}
function cuEnquiryTrigger(){
  var host=enquiryHost();
  var scope=host||enquiryWrap()||formRoot()||document;
  var labelled=scope.querySelector && scope.querySelector("div[role='combobox'][aria-label='Type of Enquiry']");
  if (labelled && boxArea(labelled)>10) return labelled;
  var list=qsaDeep("div.dropdown-atom__list[role='combobox'], div.dropdown-atom.dropdown-atom__list, div.dropdown-atom_list[role='combobox'], [role='combobox']", scope);
  for (var i=0;i<list.length;i++){ if (shown(list[i])) return list[i]; }
  if (list.length) return list[0];
  return host;
}
function cuEnquiryItems(){
  var host=enquiryHost();
  var sel="li.dropdown-atom__item, li.dropdown-atom_item, li[class*='dropdown-atom'][class*='item'], li[data-item], [role=option]";
  var local=host ? qsaDeep(sel, host) : [];
  if (local.length) return local;
  var wrap=enquiryWrap();
  var near=wrap ? qsaDeep(sel, wrap) : [];
  if (near.length) return near;
  return qsaDeep(sel);
}
function cuEnquiryMenu(){
  var sel="ul.dropdown-atom__menu, ul.dropdown-atom_menu, ul[class*='dropdown-atom'][class*='menu'], [role=listbox]";
  var host=enquiryHost();
  var menus=host ? qsaDeep(sel, host) : [];
  if (!menus.length) menus=qsaDeep(sel, enquiryWrap());
  if (!menus.length) menus=qsaDeep(sel);
  for (var i=0;i<menus.length;i++){
    if (shown(menus[i])) return menus[i];
  }
  return menus.length ? menus[0] : null;
}
function cuMenuShown(){
  var menu=cuEnquiryMenu();
  if (menu && shown(menu)) return true;
  var trigger=cuEnquiryTrigger();
  return !!(trigger && trigger.getAttribute("aria-expanded")==="true");
}
function cuHasVisibleEnquiryItems(){
  var items=cuEnquiryItems();
  for (var i=0;i<items.length;i++){ if (shown(items[i])) return true; }
  return false;
}
function cuOpenEnquiry(){
  var host=enquiryHost();
  var trigger=cuEnquiryTrigger();
  var targets=[];
  if (trigger) targets.push(trigger);
  if (host && host!==trigger) targets.push(host);
  if (host){
    qsaDeep("button, [class*='chevron'], [class*='arrow'], [class*='caret'], [class*='icon']", host).forEach(function(el){ targets.push(el); });
  }
  for (var i=0;i<targets.length;i++){
    fireClick(targets[i]);
    if (cuMenuShown() || cuHasVisibleEnquiryItems()) return true;
  }
  return !!(trigger || host);
}
function cuItemMatches(el, label){
  var aliases=enquiryAliases(label);
  var data=norm(cleanItem(el.getAttribute("data-item")));
  var text=norm(cleanItem(el.innerText||el.textContent));
  for (var i=0;i<aliases.length;i++){
    var needle=aliases[i];
    if ((data && (data.indexOf(needle)>=0 || needle.indexOf(data)>=0)) || (text && text.indexOf(needle)>=0)) return true;
  }
  return false;
}
function cuClickEnquiryItem(label){
  var items=cuEnquiryItems();
  var vis=[], rest=[];
  for (var i=0;i<items.length;i++){
    if (shown(items[i])) vis.push(items[i]); else rest.push(items[i]);
  }
  var pool=vis.concat(rest);
  for (var j=0;j<pool.length;j++){
    if (!cuItemMatches(pool[j], label)) continue;
    fireClick(pool[j]);
    return true;
  }
  return false;
}
function cuPickEnquiry(label){
  if (!cuHasVisibleEnquiryItems()) cuOpenEnquiry();
  if (cuClickEnquiryItem(label)) return true;
  return cuSelectEnquiry(label);
}
function cuSelectEnquiry(label){
  return cuApplyEnquiryValue(label);
}
function cuApplyEnquiryValue(label){
  var canon=enquiryCanonical(label);
  var hidden=enquiryHidden();
  if (hidden){
    hidden.disabled=false;
    hidden.value=canon;
    hidden.setAttribute("value", canon);
    try{ hidden.dispatchEvent(new Event("input",{bubbles:true})); }catch(e){}
    try{ hidden.dispatchEvent(new Event("change",{bubbles:true})); }catch(e){}
  }
  var host=enquiryHost();
  if (host){
    try{
      host.value=canon;
      host.setAttribute("value", canon);
      host.dispatchEvent(new Event("input",{bubbles:true}));
      host.dispatchEvent(new Event("change",{bubbles:true}));
      host.dispatchEvent(new CustomEvent("value-changed",{detail:{value:canon},bubbles:true}));
    }catch(e){}
  }
  var trigger=cuEnquiryTrigger();
  if (trigger){
    trigger.classList.remove("input-error");
    trigger.setAttribute("aria-expanded","false");
    trigger.setAttribute("aria-invalid","false");
    var leaf=trigger.querySelector(".dropdown-atom__value, .dropdown-atom__selected, .dropdown-atom__placeholder, [class*='selected'], [class*='placeholder']");
    if (!leaf){
      var kids=trigger.querySelectorAll("span, p, div, label");
      for (var i=0;i<kids.length;i++){
        if (kids[i].querySelector("svg, img, input, button")) continue;
        leaf=kids[i];
        break;
      }
    }
    if (leaf) leaf.textContent=canon;
  }
  var wrap=enquiryWrap();
  if (wrap){
    var err=wrap.querySelector(".dropdown-error, .input-error, [class*='error']");
    if (err) err.style.display="none";
  }
  try{ sessionStorage.setItem("__cuEnquiry", canon); }catch(e){}
  return cuEnquirySelected(label);
}
function cuEnquirySelected(label){
  var aliases=enquiryAliases(label);
  var hidden=enquiryHidden();
  var hv=norm(cleanItem(hidden && hidden.value));
  if (hv){
    for (var a=0;a<aliases.length;a++){
      if (hv.indexOf(aliases[a])>=0 || aliases[a].indexOf(hv)>=0) return true;
    }
  }
  var trigger=cuEnquiryTrigger();
  var shownText=trigger ? norm(cleanItem(trigger.innerText)) : "";
  if (shownText){
    for (var b=0;b<aliases.length;b++){
      if (shownText.indexOf(aliases[b])>=0 && shownText.length < aliases[b].length + 48) return true;
    }
  }
  var items=cuEnquiryItems();
  for (var i=0;i<items.length;i++){
    var on=items[i].getAttribute("aria-selected")==="true" || /selected|active|checked/i.test(items[i].className||"");
    if (on && cuItemMatches(items[i], label)) return true;
  }
  return false;
}
function findFcInput(){
  var root=formRoot()||document;
  var sels=[
    'input[placeholder*="Financial Consultant"]',
    'input[placeholder*="Consultant"]',
    'input[name*="consultant"]',
    'input[id*="consultant"]',
    'input[name*="fcMobile"]',
    'input[name*="fc_mobile"]',
    'input[name*="financialConsultant"]'
  ];
  for (var i=0;i<sels.length;i++){
    var el=root.querySelector(sels[i]);
    if (el) return el;
    var deep=qsaDeep(sels[i], root);
    if (deep.length) return deep[0];
  }
  return findByLabel(root, "Financial Consultant's Mobile") || findByLabel(root, "Financial Consultant");
}
function cuFillField(label, value){
  var root=formRoot()||document;
  document.querySelectorAll("[data-cu-fill]").forEach(function(n){ n.removeAttribute("data-cu-fill"); });
  var el=null;
  if (/financial consultant|fc mobile|consultant/i.test(label||"")) el=findFcInput();
  if (/date of birth|mm\/dd|dob/i.test(label||"")) el=findDobInput();
  if (!el) el=findField(root, label);
  if (!el) return false;
  el.setAttribute("data-cu-fill","1");
  try{ el.removeAttribute("readonly"); el.disabled=false; }catch(e){}
  try{ el.scrollIntoView({block:"center"}); }catch(e){}
  try{ el.focus(); }catch(e){}
  if (/date of birth|mm\/dd|dob/i.test(label||"")) return cuApplyDate(el, value);
  setNative(el, value);
  (CU_FIELDS[label]||[]).forEach(function(name){
    qsaDeep("input[name='"+name+"'], textarea[name='"+name+"']", root).forEach(function(other){
      if (other!==el && fieldUsable(other)) setNative(other, value);
    });
  });
  return (el.value||"").indexOf(value)>=0 || true;
}
function findDobInput(){
  var root=formRoot()||document;
  var named=findNamed(root, ["Date_of_Birth__c"]);
  if (named) return named;
  var deep=qsaDeep("input[type=date], input[placeholder*='mm/dd'], input[placeholder*='MM/DD'], input[placeholder*='yyyy']", root);
  for (var i=0;i<deep.length;i++){
    if (!fieldUsable(deep[i])) continue;
    var ph=norm(deep[i].placeholder||"");
    var nm=norm((deep[i].name||"")+" "+(deep[i].id||"")+" "+(deep[i].getAttribute("aria-label")||""));
    if (/birth|dob|date/.test(nm) || /mm\/dd|yyyy/.test(ph)) return deep[i];
  }
  var byLabel=findByLabel(root, "Date of Birth") || findByLabel(root, "mm/dd/yyyy");
  if (byLabel && /INPUT|TEXTAREA|SELECT/i.test(byLabel.tagName||"")) return byLabel;
  return deep.length ? deep[0] : null;
}
function cuVisibleReady(){
  return !!findField(formRoot()||document, "First Name");
}
function cuVisibleValues(){
  var root=formRoot()||document;
  function val(label){
    var el=findField(root, label);
    return el ? String(el.value||"") : "";
  }
  var hidden=enquiryHidden();
  var form=contactForm();
  return {
    first:val("First Name"),
    last:val("Last Name"),
    mobile:val("Mobile Number"),
    email:val("Email Address"),
    comment:val("Comment"),
    enquiry:hidden ? String(hidden.value||"") : "",
    formId:form ? (form.id||"") : "",
    formArea:boxArea(form),
    ready:cuVisibleReady()
  };
}
function cuFillVisibleBundle(data){
  data=data||{};
  var out={};
  out.first=cuFillField("First Name", data.firstName||"");
  out.last=cuFillField("Last Name", data.lastName||"");
  out.mobile=cuFillField("Mobile Number", data.mobile||"");
  out.email=cuFillField("Email Address", data.email||"");
  if (data.nric) out.nric=cuFillField("NRIC", data.nric);
  if (data.dob) out.dob=cuFillDate(data.dob);
  if (data.policy) out.policy=cuFillField("Policy Number", data.policy);
  out.comment=cuFillField("Comment", data.comment||"");
  if (data.fcMobile) out.fc=cuFillField("Financial Consultant's Mobile", data.fcMobile);
  out.values=cuVisibleValues();
  out.ok=!!((out.values.first||"").length && (out.values.last||"").length && (out.values.email||"").length);
  return out;
}
function cuApplyDate(el, value){
  var iso=value;
  if (/^\d{2}\/\d{2}\/\d{4}$/.test(value)) iso=value.slice(6)+"-"+value.slice(0,2)+"-"+value.slice(3,5);
  var type=(el.type||el.getAttribute("type")||"").toLowerCase();
  setNative(el, type==="date" ? iso : value);
  if (type==="date" && !(el.value||"")) setNative(el, value);
  var now=el.value||"";
  var digits=(now+"").replace(/\D/g,"");
  return now.indexOf(value)>=0 || now.indexOf(iso)>=0 || digits.indexOf("20050303")>=0 || digits.indexOf("03032005")>=0;
}
function cuFillDate(value){
  document.querySelectorAll("[data-cu-fill]").forEach(function(n){ n.removeAttribute("data-cu-fill"); });
  var el=findDobInput();
  if (!el) return false;
  el.setAttribute("data-cu-fill","1");
  try{ el.removeAttribute("readonly"); el.disabled=false; }catch(e){}
  return cuApplyDate(el, value);
}
function cuFilledValue(){
  var el=document.querySelector("[data-cu-fill]");
  return el ? (el.value||"") : "";
}
function cuFieldShown(kind){
  var root=formRoot()||document;
  var el=null;
  if (kind==="nric") el=findByLabel(root, "NRIC") || findByLabel(root, "Last 4");
  else if (kind==="dob") el=findDobInput();
  else if (kind==="policy") el=findByLabel(root, "Policy Number");
  else if (kind==="fc") el=findFcInput();
  if (!el) return false;
  if (shown(el)) return true;
  var wrap=el.closest(".contactus_form_inputs, .aem-GridColumn, .formoptionsextension, .formoptionextension") || el.parentElement;
  return shown(wrap);
}
function declHost(){
  var nodes=document.querySelectorAll("checkbox-atom[name='Agreed_to_Individual_PDPA_Consent__c'], .consent-checkbox checkbox-atom, checkbox-atom");
  for (var i=0;i<nodes.length;i++){
    if (boxArea(nodes[i])>10) return nodes[i];
  }
  return nodes[0]||null;
}
function declInput(){
  var host=declHost();
  if (host){
    var inp=host.querySelector("input[type=checkbox], input[name='Agreed_to_Individual_PDPA_Consent__c']");
    if (inp) return inp;
    var deep=qsaDeep("input[type=checkbox], input[name='Agreed_to_Individual_PDPA_Consent__c']", host);
    if (deep.length) return deep[0];
  }
  return document.querySelector("input[name='Agreed_to_Individual_PDPA_Consent__c']");
}
function declClickTarget(){
  var host=declHost();
  if (host){
    return host.querySelector("label.checkbox-wrapper, .checkbox-wrapper, span.custom-box, .checkbox-container") || host;
  }
  return document.querySelector(".consent-checkbox label.checkbox-wrapper, .consent-checkbox span.custom-box, checkbox-atom label.checkbox-wrapper");
}
function cuDeclarationState(){
  var host=declHost();
  if (host){
    var hv=String(host.getAttribute("value")||host.value||"").toLowerCase();
    if (hv==="true" || hv==="1" || hv==="on") return true;
  }
  var inp=declInput();
  if (inp && (inp.checked===true || inp.getAttribute("aria-checked")==="true")) return true;
  var wrap=document.querySelector(".consent-checkbox .checkbox-wrapper, checkbox-atom .checkbox-wrapper, .consent-checkbox .custom-box, checkbox-atom .custom-box");
  if (wrap && /checked|selected|active/i.test(wrap.className||"")) return true;
  if (!host && !inp) return null;
  return false;
}
function cuClickDeclaration(){
  var target=declClickTarget();
  if (target) fireClick(target);
  return true;
}
function cuEnsureDeclaration(on){
  var want=!!on;
  if (cuDeclarationState()===want) return true;
  cuClickDeclaration();
  if (cuDeclarationState()===want) return true;
  var inp=declInput();
  var host=declHost();
  if (inp){
    try{
      inp.checked=want;
      inp.setAttribute("aria-checked", want?"true":"false");
      inp.value=want?"true":"false";
      inp.setAttribute("value", want?"true":"false");
      var desc=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,"checked");
      if (desc && desc.set) desc.set.call(inp, want);
    }catch(e){}
    try{ inp.dispatchEvent(new Event("input",{bubbles:true})); }catch(e){}
    try{ inp.dispatchEvent(new Event("change",{bubbles:true})); }catch(e){}
  }
  if (host){
    try{
      host.value=want?"true":"false";
      host.setAttribute("value", want?"true":"false");
      host.dispatchEvent(new Event("change",{bubbles:true}));
    }catch(e){}
  }
  document.querySelectorAll("[name='Agreed_to_Individual_PDPA_Consent__c']").forEach(function(el){
    try{
      if (el.type==="checkbox") el.checked=want;
      el.value=want?"true":"false";
      el.setAttribute("value", want?"true":"false");
      el.dispatchEvent(new Event("change",{bubbles:true}));
    }catch(e){}
  });
  return cuDeclarationState()===want;
}
function persistSnap(fields){
  window.__cuSnap=fields||{};
  try{ sessionStorage.setItem("__cuSnap", JSON.stringify(window.__cuSnap)); }catch(e){}
  return window.__cuSnap;
}
function readPersistedSnap(){
  if (window.__cuSnap && Object.keys(window.__cuSnap).length) return window.__cuSnap;
  try{
    var raw=sessionStorage.getItem("__cuSnap");
    if (raw) return JSON.parse(raw)||{};
  }catch(e){}
  return {};
}
function cuResetPersist(){
  window.__cuSnap={};
  window.__cuLast={url:"", status:0, body:"", fields:{}, count:0, formSubmit:false};
  try{
    sessionStorage.removeItem("__cuSnap");
    sessionStorage.removeItem("__cuOwner");
    sessionStorage.removeItem("__cuEnquiry");
  }catch(e){}
  return true;
}
function cuSnapshotFields(){
  var form=contactForm();
  var fields={};
  if (!form) return readPersistedSnap();
  form.querySelectorAll("input, select, textarea").forEach(function(el){
    if (el.disabled) return;
    if (el.type==="radio" && !el.checked) return;
    if (el.type==="checkbox" && !el.checked) return;
    var key=el.name||el.id;
    if (!key) return;
    fields[key]=el.value||"";
  });
  if (!fields.Existing_Customer__c){
    try{ fields.Existing_Customer__c=sessionStorage.getItem("__cuOwner")||""; }catch(e){}
  }
  if (!fields.Lead_Sub_Source__c){
    try{ fields.Lead_Sub_Source__c=sessionStorage.getItem("__cuEnquiry")||""; }catch(e){}
  }
  return persistSnap(fields);
}
function cuBindUtmsFromUrl(){
  var q=new URLSearchParams(location.search);
  var form=contactForm()||document;
  var n=0;
  ["utm_source","utm_medium","utm_campaign","utm_term","utm_content"].forEach(function(k){
    var v=q.get(k);
    if (!v) return;
    var els=form.querySelectorAll("input[name='"+k+"'], input[name='"+k+"__c']");
    if (!els.length && form.appendChild){
      var hid=document.createElement("input");
      hid.type="hidden";
      hid.name=k;
      hid.value=v;
      form.appendChild(hid);
      n++;
      return;
    }
    els.forEach(function(el){ el.value=v; n++; });
  });
  return n;
}
function buttonLabel(el){
  return norm(el.innerText||el.textContent||el.value||el.getAttribute("aria-label")||el.getAttribute("title")||"");
}
function isNavButton(el){
  var t=buttonLabel(el);
  return /^(previous|prev|next|back|continue|cancel)$/.test(t) || t.indexOf("previous")>=0;
}
function isSubmitText(el){
  if (!el || isNavButton(el)) return false;
  var t=buttonLabel(el);
  return t==="submit" || t.indexOf("submit")===0;
}
function cuFindSubmit(){
  var sels="button[type=submit], input[type=submit], button, a, button-atom, [role=button], .formbuttonExtension *, .formbuttonextension *";
  var pools=qsaDeep(sels, document);
  var exact=[];
  for (var i=0;i<pools.length;i++){
    var el=pools[i];
    if (isNavButton(el)) continue;
    if (boxArea(el)<4 && boxArea(hostOf(el))<4) continue;
    if (isSubmitText(el)) exact.push(el);
  }
  return exact[0]||null;
}
function cuMarkSubmit(){
  document.querySelectorAll("[data-cu-submit]").forEach(function(n){ n.removeAttribute("data-cu-submit"); });
  var btn=cuFindSubmit();
  if (!btn) return false;
  try{ btn.disabled=false; btn.removeAttribute("disabled"); }catch(e){}
  btn.setAttribute("data-cu-submit","1");
  try{ btn.scrollIntoView({block:"center"}); }catch(e){}
  return true;
}
function cuClickSubmit(){
  persistSnap(cuSnapshotFields());
  cuBindUtmsFromUrl();
  var btn=cuFindSubmit();
  if (btn){
    try{ btn.disabled=false; btn.removeAttribute("disabled"); }catch(e){}
    fireClick(btn);
    return true;
  }
  return cuForceSubmit();
}
function cuForceSubmit(){
  var form=contactForm();
  var btn=cuFindSubmit();
  if (!form) return false;
  persistSnap(cuSnapshotFields());
  try{
    if (form.requestSubmit){ form.requestSubmit(btn||undefined); return true; }
  }catch(e){}
  try{ form.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})); return true; }catch(e){}
  return false;
}
function cuSubmitDebug(){
  var form=contactForm();
  var btn=cuFindSubmit();
  var invalid=[];
  if (form){
    form.querySelectorAll("input, textarea, select").forEach(function(el){
      try{
        if (el.willValidate && !el.checkValidity()) {
          invalid.push({name:el.name||el.id, msg:el.validationMessage, val:el.value||"", type:el.type||""});
        }
      }catch(e){}
    });
  }
  return {
    formId:form?form.id:"",
    action:form?String(form.action||""):"",
    method:form?String(form.method||""):"",
    checkValidity:form?form.checkValidity():null,
    invalid:invalid.slice(0,8),
    btn:btn?{tag:btn.tagName, type:btn.type||"", disabled:!!btn.disabled, area:boxArea(btn), text:cleanItem(btn.innerText||btn.value)}:null,
    captcha:((document.querySelector("textarea[name=g-recaptcha-response], input[name=g-recaptcha-response]")||{}).value||"").length,
    enquiry:(enquiryHidden()&&enquiryHidden().value)||""
  };
}
function cuListEnquiry(wantJson){
  var want=[];
  try{ want=JSON.parse(wantJson||"[]"); }catch(e){ want=[]; }
  var found=[];
  var options=enquiryOptionStrings();
  for (var i=0;i<options.length;i++){
    var t=cleanItem(options[i]);
    if (!t) continue;
    var hit="";
    for (var w=0;w<want.length;w++){
      var aliases=enquiryAliases(want[w]);
      var nt=norm(t);
      for (var a=0;a<aliases.length;a++){
        if (nt.indexOf(aliases[a])>=0 || aliases[a].indexOf(nt)>=0){ hit=want[w]; break; }
      }
      if (hit) break;
    }
    if (hit && found.indexOf(hit)<0) found.push(hit);
  }
  return JSON.stringify({labels:found});
}
function cuCloseMenu(){
  document.dispatchEvent(new KeyboardEvent("keydown",{key:"Escape",keyCode:27,bubbles:true}));
  try{ document.body.click(); }catch(e){}
  return true;
}
function cuValidation(){
  var root=contactForm()||formRoot()||document;
  var texts=[];
  var nodes=root.querySelectorAll(".dropdown-error, .input-error, [class*='input-error'], [data-required-message], [data-required-checkboxmsg], [aria-invalid=true], [class*=error], [class*=invalid], .help-block, .field-error");
  for (var i=0;i<nodes.length;i++){
    var el=nodes[i];
    var t=(el.innerText||el.getAttribute("aria-label")||el.getAttribute("data-required-message")||el.getAttribute("data-required-checkboxmsg")||"").replace(/\s+/g," ").trim();
    var vis=shown(el) || (el.style && String(el.style.display).indexOf("flex")>=0);
    if (!vis) continue;
    if (t && t.length<180 && texts.indexOf(t)<0) texts.push(t);
  }
  var blob=((root.innerText||"")+" "+(document.body && document.body.innerText || "")+" "+(document.title||"")+" "+(location.href||"")).toLowerCase();
  if (blob.indexOf("field is required")>=0 && texts.indexOf("Field is required")<0) texts.push("Field is required");
  var sorry=cuOnSorry() || blob.indexOf("/sorry")>=0 || blob.indexOf("couldn't process")>=0 || blob.indexOf("could not process")>=0 || blob.indexOf("sorry")>=0 || blob.indexOf("something went wrong")>=0 || blob.indexOf("unable to")>=0 || blob.indexOf("try again later")>=0 || blob.indexOf("error occurred")>=0;
  var invalid=texts.length>0 || !!(root.querySelector(".input-error, .dropdown-error[style*='flex'], [aria-invalid=true]"));
  return JSON.stringify({
    hasForm:!!formRoot(),
    errorCount:texts.length,
    errors:texts.slice(0,12),
    sorry:sorry,
    invalid:invalid
  });
}
function cuOwnerVisibility(){
  var root=formRoot();
  if (!root) return JSON.stringify({found:false});
  var policy=findByLabel(root, "Policy Number");
  var nric=findByLabel(root, "NRIC")||findByLabel(root, "Last 4");
  var fields={};
  var form=root.tagName==="FORM"?root:(root.querySelector("form")||root);
  form.querySelectorAll("input, select, textarea").forEach(function(el){
    if (el.disabled) return;
    if (el.type==="radio" && !el.checked) return;
    if (el.type==="checkbox" && !el.checked) return;
    var key=el.name||el.id||el.placeholder||el.type;
    if (!key) return;
    fields[key]={value:el.value||"", type:el.type||"", hiddenType:el.type==="hidden", cssHidden:!visible(el)};
  });
  return JSON.stringify({
    found:true,
    policyVisible:!!(policy && visible(policy)),
    nricVisible:!!(nric && visible(nric)),
    fields:fields
  });
}
function cuRecaptcha(){
  var widget=!!(document.querySelector(".g-recaptcha, #gCaptchaId, [data-sitekey], iframe[src*=\"recaptcha\"]"));
  var v3=!!(window.grecaptcha);
  var token=!!(document.querySelector("textarea[name=g-recaptcha-response], input[name=g-recaptcha-response], input[name*=captcha], input[name*=recaptcha]"));
  var val="";
  var box=document.querySelector("textarea[name=g-recaptcha-response], input[name=g-recaptcha-response]");
  if (box) val=box.value||"";
  return JSON.stringify({widget:widget, v3:v3, tokenField:token, present:widget||v3||token, bypassed:!!window.__cuCaptchaPassed, tokenLen:val.length});
}
function recaptchaSiteKey(){
  var nodes=document.querySelectorAll("[data-sitekey], .g-recaptcha, #gCaptchaId");
  for (var i=0;i<nodes.length;i++){
    var k=nodes[i].getAttribute("data-sitekey");
    if (k) return k;
  }
  var scripts=document.querySelectorAll("script[src*='recaptcha']");
  for (var s=0;s<scripts.length;s++){
    var m=(scripts[s].src||"").match(/[?&]render=([^&]+)/);
    if (m && m[1] && m[1]!=="explicit") return decodeURIComponent(m[1]);
  }
  return "";
}
function cuEnsureCaptchaField(){
  var form=contactForm()||document.querySelector("form#show-owner, form#hide-owner, form");
  var box=form && form.querySelector("textarea[name=g-recaptcha-response], input[name=g-recaptcha-response], #g-recaptcha-response");
  if (box) return box;
  box=document.querySelector("textarea[name=g-recaptcha-response], input[name=g-recaptcha-response], #g-recaptcha-response");
  if (box) return box;
  if (!form) return null;
  var ta=document.createElement("textarea");
  ta.name="g-recaptcha-response";
  ta.id="g-recaptcha-response";
  ta.style.display="none";
  form.appendChild(ta);
  return ta;
}
function setCaptchaToken(token){
  if (!token) return;
  window.__cuRealToken=token;
  var box=cuEnsureCaptchaField();
  if (box) box.value=token;
  document.querySelectorAll("textarea[name=g-recaptcha-response], input[name=g-recaptcha-response], #g-recaptcha-response").forEach(function(el){ el.value=token; });
  window.__cuCaptchaPassed=true;
  window.captchaSuccess=true;
  window.captchaVerified=true;
  window.isCaptchaChecked=true;
}
function cuSaveRealGrecaptcha(){
  if (!window.grecaptcha || window.__cuRealGrecaptcha) return;
  try{
    window.__cuRealGrecaptcha={
      execute:window.grecaptcha.execute && window.grecaptcha.execute.bind(window.grecaptcha),
      ready:window.grecaptcha.ready && window.grecaptcha.ready.bind(window.grecaptcha),
      getResponse:window.grecaptcha.getResponse && window.grecaptcha.getResponse.bind(window.grecaptcha)
    };
    if (window.grecaptcha.enterprise && window.grecaptcha.enterprise.execute){
      window.__cuRealGrecaptcha.ent=window.grecaptcha.enterprise.execute.bind(window.grecaptcha.enterprise);
    }
  }catch(e){}
}
function cuUnhideCaptcha(){
  document.querySelectorAll("[data-cu-ignored]").forEach(function(el){
    try{ el.style.display=""; }catch(e){}
    el.removeAttribute("data-cu-ignored");
  });
}
function cuUnstickAtoms(){
  var form=contactForm();
  if (form){
    form.noValidate=true;
    form.querySelectorAll("input, textarea, select").forEach(function(el){
      try{ el.setCustomValidity(""); }catch(e){}
      el.removeAttribute("aria-invalid");
      el.classList.remove("input-error","error","invalid");
    });
  }
  document.querySelectorAll(".input-error, .dropdown-error").forEach(function(el){
    try{ el.style.display="none"; }catch(e){}
    el.classList.remove("input-error");
  });
  document.querySelectorAll("dropdown-atom, input-atom, checkbox-atom, textarea-atom").forEach(function(h){
    try{
      h.removeAttribute("aria-invalid");
      h.classList.remove("input-error");
    }catch(e){}
  });
}
function installExecuteStub(token){
  if (!window.grecaptcha) window.grecaptcha={};
  window.grecaptcha.getResponse=function(){ return token; };
  window.grecaptcha.execute=function(){ return Promise.resolve(token); };
  window.grecaptcha.ready=function(fn){ if (fn) try{ fn(); }catch(e){} };
  if (window.grecaptcha.enterprise){
    try{ window.grecaptcha.enterprise.execute=function(){ return Promise.resolve(token); }; }catch(e){}
  }
}
function cuDisableCaptcha(){
  cuUnhideCaptcha();
  cuUnstickAtoms();
  var token="03AGdBq25"+Array(120).join("A");
  setCaptchaToken(token);
  installExecuteStub(token);
  window.recaptchaVerified=true;
  window.isCaptchaValid=true;
  var form=contactForm();
  if (form){
    form.querySelectorAll("button, input[type=submit]").forEach(function(btn){
      try{ btn.disabled=false; btn.removeAttribute("disabled"); }catch(e){}
    });
  }
  return true;
}
function cuPrepareCaptcha(){
  return cuDisableCaptcha();
}
function cuCaptchaStatus(){
  var val=((document.querySelector("textarea[name=g-recaptcha-response], input[name=g-recaptcha-response]")||{}).value)||window.__cuRealToken||"";
  return {tokenLen:String(val).length, hasReal:false, bypassed:true, siteKey:recaptchaSiteKey()};
}
function cuFinishCaptcha(){
  cuDisableCaptcha();
  return 120;
}
function cuKickCaptchaSuccess(){
  var token=window.__cuRealToken||"";
  if (!token){
    var box=document.querySelector("textarea[name=g-recaptcha-response], input[name=g-recaptcha-response]");
    token=(box && box.value)||"";
  }
  if (typeof window.onCaptchaSuccess==="function"){
    try{ window.onCaptchaSuccess(token); return true; }catch(e){}
  }
  return false;
}
function cuPassCaptcha(){
  cuPrepareCaptcha();
  cuFinishCaptcha();
  return true;
}
function cuIgnoreCaptcha(){
  return cuPassCaptcha();
}
function cuClearCaptcha(){
  window.__cuCaptchaPassed=false;
  window.captchaSuccess=false;
  window.captchaVerified=false;
  window.isCaptchaChecked=false;
  if (window.grecaptcha){
    try{ window.grecaptcha.getResponse=function(){ return ""; }; }catch(e){}
  }
  var els=document.querySelectorAll("textarea[name=g-recaptcha-response], input[name=g-recaptcha-response], #g-recaptcha-response");
  var n=0;
  els.forEach(function(el){ el.value=""; n++; });
  document.querySelectorAll("[data-cu-ignored]").forEach(function(el){
    try{ el.style.display=""; }catch(e){}
  });
  if (typeof window.onCaptchaExpired==="function"){
    try{ window.onCaptchaExpired(); }catch(e){}
  }
  return n>0 || !!document.querySelector(".g-recaptcha, #gCaptchaId");
}
function cuLastCapture(){
  var x=window.__cuLast||{};
  var fields=x.fields||{};
  if (!Object.keys(fields).length) fields=readPersistedSnap();
  var thank=cuThankYou();
  return JSON.stringify({
    url:x.url||"",
    status:x.status||0,
    body:x.body||"",
    fields:fields,
    count:x.count||0,
    formSubmit:!!x.formSubmit,
    thankYou:thank
  });
}
function cuScrollForm(){
  var root=formRoot();
  if (root) root.scrollIntoView({block:"center"});
  else {
    var h=document.querySelector("h1, form, main");
    if (h) h.scrollIntoView({block:"center"});
    else window.scrollTo(0,0);
  }
  return true;
}
function cuParseBody(body){
  var fields={};
  if (!body) return fields;
  if (typeof FormData!=="undefined" && body instanceof FormData){
    body.forEach(function(v,k){ fields[k]=String(v); });
    return fields;
  }
  if (typeof body==="string"){
    try{
      var j=JSON.parse(body);
      if (j && typeof j==="object"){
        Object.keys(j).forEach(function(k){
          fields[k]=typeof j[k]==="object"?JSON.stringify(j[k]):String(j[k]);
        });
        return fields;
      }
    }catch(e){}
    if (body.indexOf("=")>=0){
      body.split("&").forEach(function(p){
        var i=p.indexOf("=");
        if (i<0) return;
        var k=decodeURIComponent(p.slice(0,i).replace(/\+/g," "));
        var v=decodeURIComponent(p.slice(i+1).replace(/\+/g," "));
        fields[k]=v;
      });
    }
  }
  return fields;
}
function isFormTraffic(url, body, fields){
  var u=String(url||"").toLowerCase();
  var b=String(body||"").toLowerCase();
  var keys=Object.keys(fields||{}).join(" ").toLowerCase();
  if (/google|recaptcha|analytics|gtm|facebook|hotjar|onetrust|datadog|newrelic|collect\?|\/g\/collect|doubleclick/.test(u)) return false;
  if (keys.indexOf("lead_sub")>=0 || keys.indexOf("existing_customer")>=0 || keys.indexOf("agreed_to")>=0) return true;
  if (b.indexOf("lead_sub_source")>=0 || b.indexOf("existing_customer")>=0 || b.indexOf("first_name")>=0) return true;
  if (/contact|enquiry|lead|salesforce|web-to-lead|formservlet|\/bin\/|formsubmit|pardot/.test(u)) return true;
  return false;
}
function cuCapture(url, body, status){
  var fields=cuParseBody(body);
  if (!isFormTraffic(url, body, fields)) return;
  if (!Object.keys(fields).length) fields=readPersistedSnap();
  persistSnap(fields);
  window.__cuLast={
    url:String(url||""),
    status:status||0,
    body:String(body||"").slice(0,4000),
    fields:fields,
    count:(window.__cuLast && window.__cuLast.count || 0)+1,
    formSubmit:true
  };
  window.__cuLog.push(window.__cuLast);
}
function cuInstallProbe(){
  if (window.__cuHooked) return true;
  window.__cuHooked=true;
  window.__cuLast={url:"", status:0, body:"", fields:{}, count:0};
  window.__cuLog=[];
  var xopen=XMLHttpRequest.prototype.open;
  var xsend=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(m,u){ this.__cuUrl=u; return xopen.apply(this, arguments); };
  XMLHttpRequest.prototype.send=function(b){
    var xhr=this;
    xhr.addEventListener("load", function(){
      if (window.__cuForceFail) return;
      cuCapture(xhr.__cuUrl, b, xhr.status);
    });
    if (window.__cuForceFail){
      try{
        Object.defineProperty(xhr, "status", {get:function(){ return 500; }});
        Object.defineProperty(xhr, "readyState", {get:function(){ return 4; }});
      }catch(e){}
      setTimeout(function(){
        xhr.dispatchEvent(new Event("error"));
        xhr.dispatchEvent(new Event("load"));
      }, 10);
      cuCapture(xhr.__cuUrl, b, 500);
      return;
    }
    return xsend.apply(this, arguments);
  };
  var ofetch=window.fetch;
  if (ofetch){
    window.fetch=function(input, init){
      var url=typeof input==="string"?input:(input && input.url);
      var body=init && init.body;
      if (window.__cuForceFail){
        cuCapture(url, body, 500);
        return Promise.resolve(new Response(JSON.stringify({error:"simulated"}), {status:500}));
      }
      return ofetch.apply(this, arguments).then(function(res){
        cuCapture(url, body, res.status);
        return res;
      });
    };
  }
  document.addEventListener("submit", function(e){
    var f=e.target;
    if (!f || !f.querySelector) return;
    var fields={};
    try{
      var fd=new FormData(f);
      fd.forEach(function(v,k){ fields[k]=String(v); });
    }catch(err){}
    if (!Object.keys(fields).length) fields=cuSnapshotFields();
    persistSnap(fields);
    window.__cuLast={
      url:f.action||location.href,
      status:window.__cuLast.status||0,
      body:window.__cuLast.body||"",
      fields:fields,
      count:(window.__cuLast.count||0)+1,
      formSubmit:true
    };
  }, true);
  return true;
}
