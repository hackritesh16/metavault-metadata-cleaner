const $=s=>document.querySelector(s);
let selectedFile=null,cleanedBlob=null,cleanedName='',activeType='image',previewUrl=null;
const input=$('#fileInput'),drop=$('#dropzone'),processBtn=$('#process'),preserve=$('#preserve'),format=$('#format');
const accept={image:'image/jpeg,image/png,image/webp',video:'video/mp4,video/quicktime,video/webm',audio:'audio/mpeg,audio/wav,audio/flac,audio/mp4',pdf:'application/pdf',other:'*/*'};
document.querySelectorAll('.type-card').forEach(btn=>btn.addEventListener('click',()=>{
 document.querySelectorAll('.type-card').forEach(x=>x.classList.remove('active'));btn.classList.add('active');activeType=btn.dataset.type;input.accept=accept[activeType]||'*/*';clearFile();
 $('#dropzone small').textContent=activeType==='image'?'JPG, PNG or WEBP · Processed locally in your browser':'This format is planned for a future release · No upload will occur';
}));
function humanSize(n){if(n<1024)return n+' B';if(n<1048576)return(n/1024).toFixed(1)+' KB';return(n/1048576).toFixed(2)+' MB'}
function setProgress(p,label){$('#progressWrap').classList.remove('hidden');$('#progressBar').style.width=p+'%';$('#progressPct').textContent=p+'%';$('#progressText').textContent=label}
function clearProgress(){$('#progressWrap').classList.add('hidden');$('#progressBar').style.width='0%'}
function clearFile(){selectedFile=null;cleanedBlob=null;input.value='';if(previewUrl){URL.revokeObjectURL(previewUrl);previewUrl=null}$('#fileThumb').textContent='▧';$('#filePanel').classList.add('hidden');drop.classList.remove('hidden');processBtn.disabled=true;$('#result').classList.add('hidden');clearProgress()}
function choose(file){
 if(!file)return;
 if(activeType==='image'&&!/^image\/(jpeg|png|webp)$/.test(file.type)){alert('Please choose a JPG, PNG or WEBP image.');return}
 selectedFile=file;cleanedBlob=null;$('#fileName').textContent=file.name;$('#fileMeta').textContent=humanSize(file.size)+' · '+(file.type||'Unknown file type');
 if(previewUrl)URL.revokeObjectURL(previewUrl);
 if(file.type.startsWith('image/')){previewUrl=URL.createObjectURL(file);const img=document.createElement('img');img.src=previewUrl;img.alt='Selected image preview';$('#fileThumb').replaceChildren(img)}else $('#fileThumb').textContent='▤';
 $('#filePanel').classList.remove('hidden');drop.classList.add('hidden');processBtn.disabled=false;$('#result').classList.add('hidden');clearProgress();
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
 if(activeType!=='image'||!selectedFile.type.startsWith('image/')){alert('This release currently cleans JPG, PNG and WEBP images. Video, audio, PDF and document processing are coming later. Your file has not been uploaded.');return}
 processBtn.disabled=true;processBtn.innerHTML='<span>◌</span> CLEANING…';$('#result').classList.add('hidden');setProgress(12,'Reading image locally…');
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
  $('#report').textContent='The image was decoded and re-encoded locally. This removes embedded image metadata and preserves pixel dimensions. Re-encoding can change image data and file size; “Preserve original quality” is best-effort, not mathematically lossless. The original file was not changed.';
  $('#result').classList.remove('hidden');
 }catch(err){alert('Could not process this image: '+err.message);clearProgress()}
 finally{processBtn.disabled=!selectedFile;processBtn.innerHTML='<span>✦</span> CLEAN MY FILE <span class="arrow">↗</span>'}
});
$('#download').addEventListener('click',()=>{if(!cleanedBlob)return;const url=URL.createObjectURL(cleanedBlob);const a=document.createElement('a');a.href=url;a.download=cleanedName;a.click();setTimeout(()=>URL.revokeObjectURL(url),2000)});
