package ru.arshinscreen.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Editable protocol form. Limits/points must be verified against the actual methodology. */
public final class ProtocolEditor {
  public static final int REQUEST_SAVE=41;
  private final Activity activity;
  private final Runnable onBack;
  private final JSONObject device;
  private final String method;
  private final JSONArray standards;
  private final SharedPreferences prefs;
  private final LinkedHashMap<String,EditText> fields=new LinkedHashMap<>();
  private LinearLayout root,rows;
  private TextView result;
  private byte[] pending;
  private String pendingName,pendingType;
  private int rowCount=0;
  private JSONObject template;
  public ProtocolEditor(Activity a,JSONObject d, String m,JSONArray s,Runnable back){
    activity=a;device=d;method=m;standards=s;onBack=back;
    prefs=a.getSharedPreferences("protocol_editor",0);
    if("МП АПМ 43-13".equals(m)){
      try(InputStream stream=a.getAssets().open("mp_apm_43_13_template.json")){
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
        while((n=stream.read(buffer))!=-1)bytes.write(buffer,0,n);
        template=new JSONObject(bytes.toString("UTF-8"));
      }catch(Exception ignored){}
    }
  }
  private String key(){return method+"|"+device.optString("serial");}
  private int dp(int i){return (int)(i*activity.getResources().getDisplayMetrics().density+.5f);}
  private TextView text(String v,int size){TextView t=new TextView(activity);t.setText(v);t.setTextSize(size);t.setPadding(dp(4),dp(6),dp(4),dp(6));return t;}
  private void btn(String label,View.OnClickListener cb){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);root.addView(b);b.setOnClickListener(cb);}
  private EditText field(String id,String label,String fallback){
    root.addView(text(label,14));
    EditText e=new EditText(activity);e.setText(fallback);e.setSingleLine(false);e.setTextSize(15);
    root.addView(e);fields.put(id,e);return e;
  }
  private String value(String id){EditText e=fields.get(id);return e==null?"":e.getText().toString().trim();}
  private JSONObject stored(){
    try{return new JSONObject(prefs.getString(key(),"{}"));}catch(Exception e){return new JSONObject();}
  }
  public void show(){
    ScrollView scroll=new ScrollView(activity);
    root=new LinearLayout(activity);root.setOrientation(1);root.setPadding(dp(16),dp(12),dp(16),dp(30));
    scroll.addView(root);activity.setContentView(scroll);
    root.addView(text("ПРОТОКОЛ поверки СИ",23));
    root.addView(text("Методика: "+method,17));
    JSONObject saved=stored();
    field("number","№ протокола",saved.optString("number",""));
    field("date","Дата поверки",saved.optString("date",device.optString("date")));
    field("name","Наименование СИ",saved.optString("name",device.optString("name")));
    field("model","Модификация",saved.optString("model",device.optString("model")));
    field("serial","Заводской №",saved.optString("serial",device.optString("serial")));
    field("registry","Госреестр",saved.optString("registry",device.optString("registry")));
    field("verifier","Поверитель",saved.optString("verifier","Климов А.В."));
    field("means","Применяемые эталоны (можно отредактировать)",saved.optString("means",meansText()));
    if(template!=null){
      root.addView(text("Периодическая поверка — "+template.optString("revision"),16));
      JSONArray conditions=template.optJSONArray("conditions");
      if(conditions!=null){
        root.addView(text("Условия проведения поверки",17));
        for(int i=0;i<conditions.length();i++){
          JSONObject condition=conditions.optJSONObject(i);if(condition==null)continue;
          String id="condition_"+condition.optString("id");
          field(id,condition.optString("label")+" (требование: "+condition.optString("reference")+")",saved.optString(id,""));
        }
      }
      root.addView(text("Операции поверки согласно МП АПМ 43-13",17));
      JSONArray sections=template.optJSONArray("sections");
      if(sections!=null){
        for(int i=0;i<sections.length();i++){
          JSONObject section=sections.optJSONObject(i);if(section==null)continue;
          String id="operation_"+section.optString("id");
          field(id,"Пункт "+section.optString("point")+" — "+section.optString("label"),saved.optString(id,""));
        }
      }
      root.addView(text("Операции, не предусмотренные модификацией, необходимо отметить как неприменимые. Измеренные значения и результаты подтверждаются поверителем.",12));
    }
    root.addView(text("Измерения: эталон, показание СИ, предел допускаемой относительной погрешности (%)",15));
    root.addView(text("Контрольные точки и допуски заполняются по действующей методике. Незаданные точки не дают заключения о пригодности.",12));
    rows=new LinearLayout(activity);rows.setOrientation(1);root.addView(rows);
    JSONArray savedRows=saved.optJSONArray("rows");
    if(savedRows!=null&&savedRows.length()>0){
      for(int i=0;i<savedRows.length();i++)addRow(savedRows.optJSONObject(i));
    }else if(template!=null){
      JSONArray sections=template.optJSONArray("sections");
      if(sections!=null)for(int i=0;i<sections.length();i++){
        JSONObject op=sections.optJSONObject(i);
        if(op==null||!op.optString("point").startsWith("7.5."))continue;
        JSONObject preset=new JSONObject();
        try{preset.put("metric",op.optString("point")+" "+op.optString("label"));}catch(Exception ignored){}
        addRow(preset);
      }
    }else addRow(null);
    btn("Добавить точку измерения",v->addRow(null));
    result=text("",15);root.addView(result);
    btn("Рассчитать погрешности",v->calculate());
    btn("Сохранить черновик",v->saveDraft());
    btn("Сохранить Word (.docx)",v->export(false));
    btn("Сохранить PDF",v->export(true));
    btn("Вернуться к сведениям СИ",v->{saveDraft();onBack.run();});
  }
  private String meansText(){
    StringBuilder s=new StringBuilder();
    for(int i=0;i<standards.length();i++){
      JSONObject j=standards.optJSONObject(i);if(j==null)continue;
      if(s.length()>0)s.append("\n");
      s.append(j.optString("name"));
      if(!j.optString("model").isEmpty())s.append(" ").append(j.optString("model"));
      if(!j.optString("serial").isEmpty())s.append(", зав. № ").append(j.optString("serial"));
      if(!j.optString("etalonNumber").isEmpty())s.append(", рег. № ").append(j.optString("etalonNumber"));
    }
    return s.toString();
  }
  private EditText pointInput(String label,String val){
    EditText e=new EditText(activity);e.setHint(label);e.setSingleLine(true);
    e.setText(val);e.setTextSize(14);return e;
  }
  private final ArrayList<EditText[]> points=new ArrayList<>();
  private void addRow(JSONObject saved){
    LinearLayout line=new LinearLayout(activity);line.setOrientation(1);
    line.addView(text("Точка "+(points.size()+1),14));
    EditText metric=pointInput("Измеряемая величина / канал / единица",saved==null?"":saved.optString("metric"));
    EditText ref=pointInput("Эталонное значение",saved==null?"":saved.optString("reference"));
    EditText actual=pointInput("Показание СИ",saved==null?"":saved.optString("actual"));
    EditText limit=pointInput("Допуск ±, %",saved==null?"":saved.optString("limit"));
    line.addView(metric);line.addView(ref);line.addView(actual);line.addView(limit);rows.addView(line);
    points.add(new EditText[]{metric,ref,actual,limit});rowCount++;
  }
  private JSONArray collectRows(){
    JSONArray rows=new JSONArray();
    for(EditText[] r:points){
      JSONObject o=new JSONObject();
      try{o.put("metric",r[0].getText().toString());o.put("reference",r[1].getText().toString());o.put("actual",r[2].getText().toString());o.put("limit",r[3].getText().toString());}catch(Exception ignored){}
      rows.put(o);
    }
    return rows;
  }
  private JSONObject collect(){
    JSONObject doc=new JSONObject();
    try{
      for(String k:fields.keySet())doc.put(k,value(k));
      doc.put("method",method);doc.put("rows",collectRows());
    }catch(Exception ignored){}
    return doc;
  }
  private void saveDraft(){
    prefs.edit().putString(key(),collect().toString()).apply();
    Toast.makeText(activity,"Черновик сохранён",Toast.LENGTH_SHORT).show();
  }
  private Double parse(String s){
    try{return Double.valueOf(s.trim().replace(',','.'));}catch(Exception e){return null;}
  }
  private ArrayList<String> content(){
    ArrayList<String> lines=new ArrayList<>();
    lines.add("ПРОТОКОЛ поверки СИ № "+value("number"));
    lines.add("Дата поверки: "+value("date"));
    lines.add("Наименование СИ: "+value("name"));
    lines.add("Модификация: "+value("model"));
    lines.add("Заводской №: "+value("serial"));
    lines.add("Госреестр: "+value("registry"));
    lines.add("Методика поверки: "+method);
    lines.add("Поверитель: "+value("verifier"));
    lines.add("Применяемые эталоны:");
    lines.addAll(Arrays.asList(value("means").split("\n",-1)));
    if(template!=null){
      lines.add("Условия поверки:");
      JSONArray conditions=template.optJSONArray("conditions");
      if(conditions!=null)for(int i=0;i<conditions.length();i++){
        JSONObject x=conditions.optJSONObject(i);if(x!=null)lines.add(x.optString("label")+": "+value("condition_"+x.optString("id")));
      }
      lines.add("Операции поверки:");
      JSONArray sections=template.optJSONArray("sections");
      if(sections!=null)for(int i=0;i<sections.length();i++){
        JSONObject x=sections.optJSONObject(i);if(x!=null)lines.add(x.optString("point")+" "+x.optString("label")+": "+value("operation_"+x.optString("id")));
      }
    }
    lines.add("Результаты измерений:");
    boolean valid=true, failed=false;
    for(int i=0;i<points.size();i++){
      EditText[] p=points.get(i);
      String metric=p[0].getText().toString();
      String r=p[1].getText().toString(),a=p[2].getText().toString(),l=p[3].getText().toString();
      Double ref=parse(r),actual=parse(a),limit=parse(l);
      String line=(i+1)+". "+metric+"; эталон: "+r+"; показание СИ: "+a+"; допуск ±"+l+" %";
      if(ref==null||actual==null||limit==null||ref==0||limit<0){
        valid=false;line+="; погрешность не рассчитана";
      }else{
        double error=(actual-ref)/ref*100.0;
        boolean ok=Math.abs(error)<=limit;
        if(!ok)failed=true;
        line+=String.format(Locale.forLanguageTag("ru-RU"),"; δ=%.3f %% — %s",error,ok?"в пределах допуска":"превышение допуска");
      }
      lines.add(line);
    }
    lines.add(!valid?"Итоговая оценка не определена: не все измерения и допуски заполнены.":(failed?"По введённым точкам: есть превышение допуска.":"По введённым точкам: превышений допуска нет."));
    lines.add("Полное заключение о пригодности требует выполнения всех операций действующей методики.");
    return lines;
  }
  private void calculate(){
    ArrayList<String> lines=content();
    result.setText(lines.get(lines.size()-2)+"\n"+lines.get(lines.size()-1));
  }
  private String fileName(boolean pdf){
    String serial=value("serial").replaceAll("[^\\p{L}\\p{N}._-]","_");
    if(serial.isEmpty())serial="SI";
    return "Протокол_"+serial+"_"+value("date").replaceAll("[^0-9.]","_")+(pdf?".pdf":".docx");
  }
  private void export(boolean asPdf){
    try{
      saveDraft();
      ArrayList<String> lines=content();
      pending=asPdf?createPdf(lines):createDocx(lines);
      pendingType=asPdf?"application/pdf":"application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      pendingName=fileName(asPdf);
      Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(pendingType);
      intent.putExtra(Intent.EXTRA_TITLE,pendingName);
      activity.startActivityForResult(intent,REQUEST_SAVE);
    }catch(Exception e){Toast.makeText(activity,"Ошибка: "+e.getMessage(),Toast.LENGTH_LONG).show();}
  }
  public boolean handleSave(int request,int result,Intent data){
    if(request!=REQUEST_SAVE)return false;
    if(result==Activity.RESULT_OK&&data!=null&&pending!=null){
      try(OutputStream out=activity.getContentResolver().openOutputStream(data.getData(),"w")){
        if(out==null)throw new IOException("Нет доступа к файлу");
        out.write(pending);
        Toast.makeText(activity,"Сохранено: "+pendingName,Toast.LENGTH_LONG).show();
      }catch(Exception e){Toast.makeText(activity,"Ошибка сохранения: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    return true;
  }
  private static String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
  private static void zip(ZipOutputStream z,String name,String content)throws IOException{
    z.putNextEntry(new ZipEntry(name));z.write(content.getBytes(StandardCharsets.UTF_8));z.closeEntry();
  }
  private byte[] createDocx(ArrayList<String> lines)throws Exception{
    StringBuilder body=new StringBuilder();
    for(String line:lines)body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(xml(line)).append("</w:t></w:r></w:p>");
    String doc="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+body+"<w:sectPr/></w:body></w:document>";
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    try(ZipOutputStream z=new ZipOutputStream(bytes)){
      zip(z,"[Content_Types].xml","<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
      zip(z,"_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
      zip(z,"word/document.xml",doc);
    }
    return bytes.toByteArray();
  }
  private byte[] createPdf(ArrayList<String> source)throws Exception{
    Paint p=new Paint(3);p.setTypeface(Typeface.create("sans-serif",0));p.setTextSize(11);p.setColor(Color.BLACK);
    ArrayList<String> lines=new ArrayList<>();
    for(String s:source){
      if(s.isEmpty()){lines.add("");continue;}
      String remaining=s;
      while(!remaining.isEmpty()){int n=p.breakText(remaining,true,510,null);n=Math.max(1,n);lines.add(remaining.substring(0,n));remaining=remaining.substring(n);}
    }
    PdfDocument doc=new PdfDocument();int i=0,page=1;
    do{
      PdfDocument.Page pg=doc.startPage(new PdfDocument.PageInfo.Builder(595,842,page++).create());
      Canvas canvas=pg.getCanvas();int y=48;
      while(i<lines.size()&&y<800){canvas.drawText(lines.get(i++),38,y,p);y+=17;}
      doc.finishPage(pg);
    }while(i<lines.size());
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();doc.writeTo(bytes);doc.close();
    return bytes.toByteArray();
  }
}
