# Standalone PDF split tool: shrink Compose + PDFBox without breaking PDF internals.
-dontwarn com.tom_roush.**
-dontwarn org.apache.fontbox.**
-dontwarn org.apache.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**

# SecurityHandler subclasses are constructed reflectively by the PDFBox AAR
# consumer rules already; these cover the remaining optional paths.
-keep class com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange {
    public <init>(com.tom_roush.pdfbox.cos.COSDictionary);
}
-keep class com.tom_roush.pdfbox.filter.JPXFilter {
    public <init>();
}
