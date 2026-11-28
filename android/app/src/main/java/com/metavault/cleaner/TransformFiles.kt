package com.metavault.cleaner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val TRANSFORM_LIMIT = 512L * 1024 * 1024
internal data class ToolSource(val uri: Uri,val name: String,val mime: String,val size: Long?,val seed: File? = null) {
    val family get() = family(name,mime)
    val ext get() = name.substringAfterLast('.', "").lowercase()
}
internal data class ToolResult(val source: ToolSource,val file: File,val originalBytes: Long,val ext: String,val mime: String,
    val detail: String,val target: Long?,val sourceHash: ByteArray?) {
    val overwriteAllowed get() = (source.ext == ext || (source.ext in listOf("jpg","jpeg") && ext in listOf("jpg","jpeg"))) &&
            originalBytes <= MetadataEngine.MAX_BYTES && file.length() <= MetadataEngine.MAX_BYTES
}
internal fun toolSource(context: Context, uri: Uri): ToolSource {
    var name=uri.lastPathSegment?.substringAfterLast('/') ?: "File"
    var bytes: Long? = null
    context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use {
        if(it.moveToFirst()) {
            name=it.getString(0) ?: name
            if(!it.isNull(1)) bytes=it.getLong(1).takeIf { n -> n>=0 }
        }
    }
    return ToolSource(uri,name,context.contentResolver.getType(uri).orEmpty(),bytes)
}
internal fun transfer(input: InputStream, output: OutputStream?, limit: Long, active: ()->Unit): ByteArray {
    val digest=MessageDigest.getInstance("SHA-256")
    val buf=ByteArray(65536)
    var size=0L
    while(true) {
        active()
        val n=input.read(buf)
        if(n<0) break
        size+=n
        require(n>0 && size<=limit) { "File exceeds the ${sizeText(limit)} processing limit." }
        output?.write(buf,0,n)
        digest.update(buf,0,n)
    }
    return digest.digest()
}

internal fun prepareTransform(context: Context,source: ToolSource,plan: TransformPlan,dir: File,
    active: ()->Unit,progress: (Float)->Unit): ToolResult {
    val local=source.seed ?: File.createTempFile("source-", ".tmp",dir)
    val format=plan.format(source.family)
    val ext=if(format==Output.ORIGINAL) source.ext else format.ext
    val result=File.createTempFile("result-",".tmp",dir)
    try {
        val limit=if(plan.metadataOnly || source.family in listOf(FileFamily.OFFICE,FileFamily.PDF)) MetadataEngine.MAX_BYTES else TRANSFORM_LIMIT
        require((source.size ?: 0)<=limit || source.seed!=null) { "Input exceeds ${sizeText(limit)}." }
        val hash=if(source.seed==null) {
            val input=context.contentResolver.openInputStream(originalUri(context,source.uri)) ?: error("Cannot open input.")
            input.use { local.outputStream().use { out -> transfer(it,out,limit,active) } }
        } else null
        active()
        require(local.length()>0 && dir.usableSpace > 32L.shl(20)) { "Empty input or insufficient temporary storage." }
        val detail=if(plan.metadataOnly) {
            val kind=BatchFormat.resolve(source.name,source.mime) ?: error("Metadata cleaning is unsupported for this format.")
            val copy=prepareBatchCopy(kind,local.readBytes())
            result.writeBytes(copy.bytes)
            copy.scope
        } else when(source.family) {
            FileFamily.IMAGE -> transformImage(local,result,plan,active)
            FileFamily.VIDEO -> transformVideo(local,result,plan,active,progress)
            FileFamily.PDF -> transformPdf(context,local,result,plan,active)
            FileFamily.OFFICE -> transformOffice(local,result,source.ext,active)
            else -> error("Unsupported input format.")
        }
        active()
        check(result.length() in 1..TRANSFORM_LIMIT) { "Output is empty or exceeds the size limit." }
        return ToolResult(source,result,if(source.seed!=null) source.size ?: local.length() else local.length(),ext,
            if(format==Output.ORIGINAL) source.mime.ifBlank { BatchFormat.resolve(source.name,null)?.mime ?: "application/octet-stream" } else format.mime,
            detail,plan.target,hash)
    } catch(e: Throwable) {
        result.delete()
        throw e
    } finally { if(source.seed==null) local.delete() }
}

/** Only a document created by this invocation is eligible for partial-copy cleanup. */
internal fun saveTransform(context: Context,tree: Uri?,result: ToolResult,stem: String,sources: SelectedSources,active: ()->Unit): String {
    val resolver=context.contentResolver
    require(validStem(stem))
    if(tree==null) {
        require(result.overwriteAllowed) { "Use a new copy for this format or file size." }
        val backup=backupOriginal(context,result.source.uri,result.source.name)
        result.sourceHash?.let { expected ->
            check(backup.inputStream().use { backupDigest(it) }.contentEquals(expected)) { "The original changed. Prepare a new preview." }
        }
        active()
        // Existing verified replacement includes rollback; cancellation happens between replacements.
        writeWithRecovery(context,result.source.uri,result.file.readBytes(),backup)
        return result.source.name
    }
    val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
    val name="$stem.${result.ext}"
    val uri=DocumentsContract.createDocument(resolver,parent,result.mime,name) ?: error("Cannot create output file.")
    check(!sources.contains(uri)) { "Provider returned an original. Choose another folder." }
    try {
        val written=result.file.inputStream().use { input ->
            (resolver.openOutputStream(uri,"wt") ?: error("Cannot write output.")).use { transfer(input,it,TRANSFORM_LIMIT,active) }
        }
        check(resolver.openInputStream(uri)?.use { transfer(it,null,TRANSFORM_LIMIT,active) }?.contentEquals(written)==true) {
            "Saved file did not pass verification."
        }
        return displayName(context,uri) ?: name
    } catch(e: Exception) {
        check(runCatching { DocumentsContract.deleteDocument(resolver,uri) }.getOrDefault(false)) { "A partial new copy may remain in the destination." }
        throw e
    }
}
