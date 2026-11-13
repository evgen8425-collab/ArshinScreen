package ru.arshinscreen.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.InputStream;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Imports new Arkhin SI PDFs from a user-authorized document tree on each refresh. */
public final class SiFolderSync {
  private final Context context;
  private final SharedPreferences prefs;
  public SiFolderSync(Context context) {
    this.context=context.getApplicationContext();
    this.prefs=context.getSharedPreferences("si_folder_sync",Context.MODE_PRIVATE);
    PDFBoxResourceLoader.init(this.context);
  }
  public JSONArray cached() {
    try {return new JSONArray(prefs.getString("catalog","[]"));}catch(Exception ignored){return new JSONArray();}
  }
  public int sync(Uri tree) throws Exception {
    ContentResolver resolver=context.getContentResolver();
    String treeId=DocumentsContract.getTreeDocumentId(tree);
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,treeId);
    JSONArray catalog=cached();
    HashSet<String> known=new HashSet<>();
    for(int i=0;i<catalog.length();i++) {JSONObject item=catalog.optJSONObject(i);if(item!=null)known.add(item.optString("source_uri"));}
    int added=0;
    String[] cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
    try(Cursor cursor=resolver.query(children,cols,null,null,null)){
      if(cursor==null)throw new IllegalStateException("Папка недоступна для чтения");
      while(cursor.moveToNext()) {
        String docId=cursor.getString(0),filename=cursor.getString(1),mime=cursor.getString(2);
        if(filename==null||(!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")&&!"application/pdf".equals(mime)))continue;
        Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,docId);
        String uri=doc.toString();
        if(known.contains(uri))continue;
        try(InputStream input=resolver.openInputStream(doc)){
          if(input==null)continue;
          try(PDDocument pdf=PDDocument.load(input)){
            String text=new PDFTextStripper().getText(pdf);
            JSONObject item=parse(text);
            if(item==null)continue;
            item.put("source_uri",uri);
            item.put("file",filename);
            catalog.put(item);
            known.add(uri);added++;
          }
        }catch(Exception ignored){ /* A malformed PDF must not prevent other imports. */ }
      }
    }
    prefs.edit().putString("catalog",catalog.toString()).putLong("last_sync",System.currentTimeMillis()).apply();
    return added;
  }
  private static String capture(String text,String field){
    Pattern regex=Pattern.compile(Pattern.quote(field)+"\\s*:\\s*(.*?)(?=\\R[^\\r\\n]*?(?:Результат \\(result\\)|Аршин ·)|$)",Pattern.DOTALL);
    Matcher m=regex.matcher(text);
    if(!m.find())return "";
    return m.group(1).replaceAll("\\s+"," ").trim();
  }
  private static JSONObject parse(String text)throws Exception {
    String method=capture(text,"Методика поверки (docTitle)");
    String serial=capture(text,"Заводской номер (manufactureNum)");
    if(method.isEmpty()||serial.isEmpty())return null;
    JSONObject item=new JSONObject();
    item.put("method",method);
    item.put("serial",serial);
    item.put("name",capture(text,"Наименование типаСИ (mitypeTitle)"));
    item.put("model",capture(text,"Модификация (modification)"));
    item.put("registry",capture(text,"Госреестр (mitypeNumber)"));
    item.put("date",capture(text,"Дата поверки (vrfDate)"));
    Matcher link=Pattern.compile("https://fgis\\.gost\\.ru/fundmetrology/cm/results/[^\\s]+").matcher(text);
    item.put("url",link.find()?link.group():"");
    return item;
  }
}
