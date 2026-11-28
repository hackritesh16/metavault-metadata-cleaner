package com.metavault.cleaner

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.*

class TransformPlanTest {
    @Test fun aspectRatioPercentResolutionAndLimits(){
        assertEquals(960 to 540,TransformPlan(percent=50).dimensions(1920,1080))
        assertEquals(720 to 1280,TransformPlan(resize=Resize.RESOLUTION).dimensions(1080,1920,true))
        assertEquals(400 to 300,TransformPlan(resize=Resize.PIXELS,width=400,height=300,lockRatio=false).dimensions(800,400))
        assertThrows(IllegalArgumentException::class.java){TransformPlan(percent=400).dimensions(8000,8000)}
    }
    @Test fun targetSearchKeepsBestUnderBudgetAndStopsAtQualityFloor(){
        var current=0;var kept=0
        fun run(target:Long?) { searchQuality(90,target,{q->current=q;q*100L},{kept=current}) }
        run(7540);assertEquals(75,kept)
        run(100);assertEquals(35,kept)
        run(null);assertEquals(90,kept)
    }
    @Test fun officeRepackPreservesEveryEntryAndOriginal(){
        val dir=java.nio.file.Files.createTempDirectory("office-test").toFile()
        try {
            for((ext,part) in mapOf("docx" to "word/document.xml","xlsx" to "xl/workbook.xml","pptx" to "ppt/presentation.xml")){
                val src=File(dir,"f.$ext");val out=File(dir,"out.$ext")
                ZipOutputStream(src.outputStream()).use { zip ->
                    zip.setLevel(0)
                    for(name in listOf("[Content_Types].xml",part,"media/image.png")){
                        zip.putNextEntry(ZipEntry(name));zip.write(name.repeat(300).toByteArray());zip.closeEntry()
                    }
                }
                val original=src.readBytes()
                transformOffice(src,out,ext){}
                assertTrue(out.length()<src.length());assertArrayEquals(original,src.readBytes())
                ZipFile(out).use { zip -> for(entry in zip.entries()){
                    assertEquals(entry.name.repeat(300),zip.getInputStream(entry).bufferedReader().readText())
                } }
            }
        }finally{dir.deleteRecursively()}
    }
}
