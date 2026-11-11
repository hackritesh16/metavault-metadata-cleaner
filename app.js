const $=s=>document.querySelector(s);
let selectedFile=null,cleanedBlob=null,cleanedName='',activeType='image',previewUrl=null;
const ICON_IMAGE='<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="1.6"/><path d="m21 15-4.5-4.5L6 21"/></svg>';
const ICON_FILE='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h6"/></svg>';
const input=$('#fileInput'),drop=$('#dropzone'),processBtn=$('#process'),preserve=$('#preserve'),format=$('#format');
const accept={image:'image/jpeg,image/png,image/webp',video:'video/mp4,video/quicktime,video/webm',audio:'audio/mpeg,audio/wav,audio/flac,audio/mp4',pdf:'application/pdf',other:'*/*'};
function showNotice(msg){const n=$('#notice');n.textContent=msg;n.classList.remove('hidden');n.scrollIntoView({block:'nearest',behavior:'smooth'})}
function hideNotice(){$('#notice').classList.add('hidden')}
document.querySelectorAll('.type-card').forEach(btn=>btn.addEventListener('click',()=>{
 document.querySelectorAll('.type-card').forEach(x=>{x.classList.remove('active');x.setAttribute('aria-pressed','false')});btn.classList.add('active');btn.setAttribute('aria-pressed','true');activeType=btn.dataset.type;input.accept=accept[activeType]||'*/*';clearFile();
 $('#dropzone small').textContent=activeType==='image'?'JPG, PNG or WEBP. Processed in your browser, nothing is uploaded.':'This format is planned for a future release. Nothing will be uploaded.';
}));
function humanSize(n){if(n<1024)return n+' B';if(n<1048576)return(n/1024).toFixed(1)+' KB';return(n/1048576).toFixed(2)+' MB'}
function setProgress(p,label){$('#progressWrap').classList.remove('hidden');$('#progressBar').style.width=p+'%';$('#progressPct').textContent=p+'%';$('#progressText').textContent=label}
function clearProgress(){$('#progressWrap').classList.add('hidden');$('#progressBar').style.width='0%'}
function clearFile(){selectedFile=null;cleanedBlob=null;input.value='';if(previewUrl){URL.revokeObjectURL(previewUrl);previewUrl=null}$('#fileThumb').innerHTML=ICON_IMAGE;$('#filePanel').classList.add('hidden');drop.classList.remove('hidden');processBtn.disabled=true;$('#result').classList.add('hidden');hideNotice();clearProgress();inspectToken++;$('#findings').classList.add('hidden')}
function choose(file){
 if(!file)return;
 if(activeType==='image'&&!/^image\/(jpeg|png|webp)$/.test(file.type)){showNotice('This file isn’t supported. Choose a JPG, PNG or WEBP image.');return}
 hideNotice();
 selectedFile=file;cleanedBlob=null;$('#fileName').textContent=file.name;$('#fileMeta').textContent=humanSize(file.size)+' · '+(file.type||'Unknown file type');
 if(previewUrl)URL.revokeObjectURL(previewUrl);
 if(file.type.startsWith('image/')){previewUrl=URL.createObjectURL(file);const img=document.createElement('img');img.src=previewUrl;img.alt='Selected image preview';$('#fileThumb').replaceChildren(img)}else $('#fileThumb').innerHTML=ICON_FILE;
 $('#filePanel').classList.remove('hidden');drop.classList.add('hidden');processBtn.disabled=false;$('#result').classList.add('hidden');clearProgress();
 inspect(file);
}
input.addEventListener('change',e=>choose(e.target.files[0]));
['dragenter','dragover'].forEach(ev=>drop.addEventListener(ev,e=>{e.preventDefault();drop.classList.add('drag')}));
['dragleave','drop'].forEach(ev=>drop.addEventListener(ev,e=>{e.preventDefault();drop.classList.remove('drag')}));
drop.addEventListener('drop',e=>choose(e.dataTransfer.files[0]));
$('#removeFile').addEventListener('click',clearFile);
preserve.addEventListener('change',()=>{format.disabled=preserve.checked;$('#formatHint').textContent=preserve.checked?'Original format is retained':'Choose a new output format'});
function outputExtension(mime){return mime==='image/jpeg'?'jpg':mime==='image/webp'?'webp':'png'}
function outputMime(){return preserve.checked?selectedFile.type:format.value}
processBtn.addEventListener('click',async()=>{
 if(!selectedFile)return;
 if(activeType!=='image'||!selectedFile.type.startsWith('image/')){showNotice('This release cleans JPG, PNG and WEBP images only. Video, audio, PDF and document cleaning are coming later. Your file has not been uploaded.');return}
 hideNotice();
 processBtn.disabled=true;processBtn.innerHTML='<span class="spinner" aria-hidden="true"></span>Cleaning…';$('#result').classList.add('hidden');setProgress(12,'Reading image locally…');
 try{
  if(!('createImageBitmap'in window))throw new Error('This browser does not support image decoding. Try a recent Chrome or Firefox version.');
  const image=await createImageBitmap(selectedFile);setProgress(42,'Rebuilding image without embedded metadata…');
  const canvas=document.createElement('canvas');canvas.width=image.width;canvas.height=image.height;
  const ctx=canvas.getContext('2d',{alpha:true});
  const mime=outputMime();
  if(mime==='image/jpeg'){ctx.fillStyle='#ffffff';ctx.fillRect(0,0,canvas.width,canvas.height)}
  ctx.drawImage(image,0,0);image.close();setProgress(76,'Exporting clean copy…');
  cleanedBlob=await new Promise((resolve,reject)=>canvas.toBlob(b=>b?resolve(b):reject(new Error('This output format is not supported by your browser.')),mime,mime==='image/png'?undefined:0.96));
  if(!cleanedBlob.type)throw new Error('The browser could not create the selected output format.');
  const ext=outputExtension(cleanedBlob.type);cleanedName=selectedFile.name.replace(/\.[^.]+$/,'')+'.clean.'+ext;
  setProgress(100,'Finished — your clean copy is ready');$('#resultTitle').textContent='Your file is clean';$('#resultSub').textContent='A new copy is ready to download.';$('#outName').textContent=cleanedName;$('#outSize').textContent=humanSize(cleanedBlob.size);
  $('#report').textContent='The image was decoded and re-encoded locally. This removes embedded image metadata and preserves pixel dimensions. Re-encoding can change image data and file size; “Keep original format and quality” is best-effort, not mathematically lossless. The original file was not changed.';
  $('#result').classList.remove('hidden');
 }catch(err){showNotice('Could not process this image: '+err.message);clearProgress()}
 finally{processBtn.disabled=!selectedFile;processBtn.textContent='Clean file'}
});
$('#download').addEventListener('click',()=>{if(!cleanedBlob)return;const url=URL.createObjectURL(cleanedBlob);const a=document.createElement('a');a.href=url;a.download=cleanedName;a.click();setTimeout(()=>URL.revokeObjectURL(url),2000)});

/* ---- Metadata viewer: reads the file on this device and lists what it reveals ---- */
let inspectToken=0;
const MAX_READ=64*1048576;
// Skip fields that hold version numbers, dates or tool names, which look like IPs by accident.
const SKIP_IP_KEYS=/version|software|tool|agent|firmware|date|time|uuid|unique|documentid|instanceid/i;
const IPV4=/(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?!\d|\.\d)/g;
function textOf(v){
 if(v==null)return'';
 if(typeof v==='string')return v.replace(/\0/g,'').trim();
 if(typeof v==='number')return String(v);
 if(Array.isArray(v))return v.map(textOf).filter(Boolean).join(', ');
 return'';
}
function pick(tags,keys){for(const k of keys){const t=textOf(tags[k]);if(t)return t}return''}
function isIPv6(s){if(!s.includes(':')||/[^0-9a-f:.]/i.test(s))return false;try{new URL('http://['+s+']/');return true}catch(e){return false}}
function findIPs(tags){
 const found=new Map();
 for(const [key,val] of Object.entries(tags)){
  if(SKIP_IP_KEYS.test(key))continue;
  const text=textOf(val);if(!text)continue;
  for(const m of text.matchAll(IPV4))found.set(m[0],key);
  for(const tok of text.split(/[\s,;"'<>()\[\]{}=]+/)){const t=tok.replace(/[.,;]+$/,'');if(t.includes(':')&&isIPv6(t))found.set(t,key)}
 }
 return [...found.entries()].map(([ip,key])=>ip+'  (in '+key+')');
}
function locationOf(tags){
 const lat=tags.latitude,lon=tags.longitude;
 if(typeof lat!=='number'||typeof lon!=='number'||!isFinite(lat)||!isFinite(lon))return'';
 return Math.abs(lat).toFixed(6)+'° '+(lat>=0?'N':'S')+', '+Math.abs(lon).toFixed(6)+'° '+(lon>=0?'E':'W');
}
function buildRows(tags){
 const rows=[];
 const loc=locationOf(tags);if(loc)rows.push({label:'Location',value:loc,sensitive:true});
 const ips=findIPs(tags);if(ips.length)rows.push({label:'Possible IP address',value:ips.join('\n'),sensitive:true});
 const author=pick(tags,['Artist','creator','Byline','XPAuthor','OwnerName','CameraOwnerName']);if(author)rows.push({label:'Author',value:author,sensitive:true});
 const make=pick(tags,['Make']),model=pick(tags,['Model']);
 const camera=model.toLowerCase().startsWith(make.toLowerCase())?model:[make,model].filter(Boolean).join(' ');
 if(camera)rows.push({label:'Camera',value:camera});
 const taken=pick(tags,['DateTimeOriginal','CreateDate','DateTimeDigitized','DateTime','ModifyDate']).replace(/^(\d{4}):(\d{2}):(\d{2})/,'$1-$2-$3');
 if(taken)rows.push({label:'Date taken',value:taken});
 const software=pick(tags,['Software','CreatorTool','ProcessingSoftware']);if(software)rows.push({label:'Software',value:software});
 const copyright=pick(tags,['Copyright','rights','CopyrightNotice']);if(copyright)rows.push({label:'Copyright',value:copyright});
 const comment=pick(tags,['UserComment','ImageDescription','description','Caption','XPComment']);if(comment)rows.push({label:'Comment',value:comment});
 return rows;
}
// exifr does not open WEBP, so pull the EXIF chunk out of the RIFF container ourselves.
function webpExifChunk(buf){
 const dv=new DataView(buf.buffer,buf.byteOffset,buf.byteLength);
 const tag=(a,o)=>String.fromCharCode(a[o],a[o+1],a[o+2],a[o+3]);
 if(buf.length<12||tag(buf,0)!=='RIFF'||tag(buf,8)!=='WEBP')return null;
 let o=12;
 while(o+8<=buf.length){
  const id=tag(buf,o),len=dv.getUint32(o+4,true),s=o+8;
  if(id==='EXIF'){let d=buf.subarray(s,s+len);if(d.length>6&&tag(d,0)==='Exif')d=d.subarray(6);return d}
  o=s+len+(len&1);
 }
 return null;
}
async function readTags(file){
 const opts={tiff:true,xmp:true,iptc:true,gps:true,icc:false,jfif:false,ihdr:false,mergeOutput:true,translateValues:true,reviveValues:false,sanitize:true};
 if(file.type==='image/webp'){
  const chunk=webpExifChunk(new Uint8Array(await file.arrayBuffer()));
  return chunk?(await exifr.parse(chunk,opts))||{}:{};
 }
 try{return (await exifr.parse(file,opts))||{}}
 catch(e){if(/unknown file format/i.test(e.message))return{};throw e}
}
async function inspect(file){
 const token=++inspectToken;
 const box=$('#findings'),list=$('#findingsList'),note=$('#findingsNote'),badge=$('#findingsBadge');
 list.replaceChildren();badge.textContent='';badge.classList.remove('alert');box.classList.remove('hidden');
 if(!/^image\/(jpeg|png|webp)$/.test(file.type)){note.textContent='MetaVault can read metadata from JPG, PNG and WEBP images for now.';return}
 if(typeof exifr==='undefined'){note.textContent='The metadata reader did not load. Reload the page and try again.';return}
 if(file.size>MAX_READ){note.textContent='This file is too large to read here. Cleaning still works.';return}
 note.textContent='Reading metadata…';
 let rows;
 try{const tags=await readTags(file);if(token!==inspectToken)return;rows=buildRows(tags)}
 catch(err){if(token!==inspectToken)return;note.textContent='Could not read metadata from this file: '+err.message;return}
 const sensitive=rows.filter(r=>r.sensitive).length;
 for(const r of rows){
  const row=document.createElement('div');row.className='f-row'+(r.sensitive?' sensitive':'');
  const dt=document.createElement('dt');
  if(r.sensitive){const s=document.createElement('span');s.className='sr-only';s.textContent='Sensitive: ';dt.append(s)}
  dt.append(r.label);
  const dd=document.createElement('dd');dd.textContent=r.value;
  row.append(dt,dd);list.append(row);
 }
 if(sensitive){badge.textContent=sensitive+' sensitive '+(sensitive===1?'item':'items');badge.classList.add('alert')}
 else badge.textContent=rows.length?'No location or author':'Nothing found';
 note.textContent=rows.length
  ?'All of this is removed from your clean copy. Photos don’t normally store an IP address, so MetaVault only flags text that looks like one.'
  :'No location, camera, author or IP data found in what MetaVault can read.';
}
