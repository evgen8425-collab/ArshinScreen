package ru.arshinscreen.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.InputType;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private static final String API="https://fgis.gost.ru/fundmetrology/eapi/vri";
  private static final int PICK_FOLDER=1, PICK_SAVE=2;
  private final ExecutorService pool=Executors.newSingleThreadExecutor();
  private LinearLayout body, matches;
  private TextView message;
  private EditText serial, verifier, from, to;
  private Uri folder;
  private byte[] currentPdf;
  private String currentName;
  private final ArrayList<JSONObject> records=new ArrayList<>();
  private static final int BLUE=0xff15385a;
  private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
  private void addFieldLabel(String s){TextView t=label(s,14);t.setTextColor(BLUE);body.addView(t);}
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xfff7f9fc);getWindow().setNavigationBarColor(0xfff7f9fc);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);String u=getPreferences(0).getString("folder",null);if(u!=null)folder=Uri.parse(u);methodScreen();}
  private TextView label(String value,int sp){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setPadding(8,12,8,12);return t;}
  private void button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(48));body.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());}
  private EditText input(String hint,String value){EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(16);e.setHint(hint);e.setText(value);e.setSelectAllOnFocus(false);body.addView(e,new LinearLayout.LayoutParams(-1,dp(52)));return e;}
  private void page(String title){
    ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(16),dp(12),dp(16),dp(28));scroll.addView(body);setContentView(scroll);
    TextView heading=label(title,22);heading.setTextColor(BLUE);heading.setTypeface(null,1);body.addView(heading);
    TextView version=label("Версия "+BuildConfig.VERSION_NAME,12);
    version.setTextColor(0xff667789);
    version.setGravity(Gravity.END);
    body.addView(version);
    LinearLayout tabs=new LinearLayout(this);body.addView(tabs);
    for(String s:new String[]{"Методики","Поиск","Настройки","Файлы"}){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(11);b.setMinHeight(dp(44));b.setPadding(dp(1),0,dp(1),0);tabs.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));b.setOnClickListener(v->{if(s.equals("Методики"))methodScreen();else if(s.equals("Поиск"))searchScreen();else if(s.equals("Настройки"))settings();else files();});}
    message=label("",14);body.addView(message);
  }
  private void info(String s){runOnUiThread(()->{if(message!=null)message.setText(s);});}
  private String pref(String k,String d){return getPreferences(0).getString(k,d);}
  private String today(){return new SimpleDateFormat("dd.MM.yy",Locale.US).format(new Date());}
  private String showDate(String raw){
    if(raw.matches("\\d{4}-\\d{2}-\\d{2}"))return raw.substring(8,10)+"."+raw.substring(5,7)+"."+raw.substring(2,4);
    return raw;
  }
  private String isoDate(String displayed){
    if(!displayed.matches("\\d{2}\\.\\d{2}\\.\\d{2}"))return null;
    try{
      SimpleDateFormat fmt=new SimpleDateFormat("dd.MM.yy",Locale.US);
      fmt.setLenient(false);
      Date date=fmt.parse(displayed);
      if(date==null)return null;
      int yr=Integer.parseInt(displayed.substring(6,8));
      if(new SimpleDateFormat("yyyy",Locale.US).format(date).equals(""+(2000+yr)))
        return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(date);
    }catch(Exception ignored){}
    return null;
  }
  private void rememberDate(String key,String date){
    if(isoDate(date)!=null)getPreferences(0).edit().putString(key,date).apply();
  }
  private EditText dateInput(String key,String fallback){
    EditText e=input("ДД.ММ.ГГ",showDate(pref(key,fallback)));
    e.setInputType(InputType.TYPE_CLASS_NUMBER);
    e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
    e.setText(showDate(pref(key,fallback)));
    e.addTextChangedListener(new TextWatcher(){
      private boolean changing=false;
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){}
      public void afterTextChanged(Editable editable){
        if(changing)return;
        changing=true;
        String digits=editable.toString().replaceAll("[^0-9]","");
        if(digits.length()>6)digits=digits.substring(0,6);
        StringBuilder formatted=new StringBuilder();
        for(int i=0;i<digits.length();i++){
          if(i==2||i==4)formatted.append('.');
          formatted.append(digits.charAt(i));
        }
        String value=formatted.toString();
        if(!value.contentEquals(editable)){e.setText(value);e.setSelection(value.length());}
        rememberDate(key,value);
        changing=false;
      }
    });
    // Dots are inserted automatically, but the field remains numeric-keyboard friendly.
    return e;
  }
  private JSONArray catalog(){
    try(InputStream stream=getAssets().open("si_catalog.json")){
      ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
      while((n=stream.read(buf))!=-1)b.write(buf,0,n);
      return new JSONArray(b.toString("UTF-8"));
    }catch(Exception e){info("Не удалось открыть справочник СИ: "+e.getMessage());return new JSONArray();}
  }
  private void methodScreen(){
    page("Методики СИ");
    body.addView(label("Приборы из Google Диска / АршинСкрин / Сведения си",14));
    JSONArray all=catalog();
    java.util.TreeMap<String,ArrayList<JSONObject>> groups=new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for(int i=0;i<all.length();i++){
      JSONObject item=all.optJSONObject(i);if(item==null)continue;
      String method=item.optString("method","Без методики");
      if(!groups.containsKey(method))groups.put(method,new ArrayList<>());
      groups.get(method).add(item);
    }
    body.addView(label("Методик: "+groups.size()+" • СИ: "+all.length(),16));
    body.addView(label("Справочник загружен из документов, доступных при выпуске приложения. Новые файлы на Диске потребуют обновления данных.",12));
    for(java.util.Map.Entry<String,ArrayList<JSONObject>> group:groups.entrySet()){
      String method=group.getKey();ArrayList<JSONObject> devices=group.getValue();
      Button b=new Button(this);b.setAllCaps(false);b.setTextSize(14);
      b.setText(method+"\nСИ: "+devices.size());body.addView(b);
      b.setOnClickListener(v->methodDevices(method,devices));
    }
  }
  private void methodDevices(String method,ArrayList<JSONObject> devices){
    page("Методика поверки");
    body.addView(label(method,18));
    body.addView(label("Средства измерений: "+devices.size(),14));
    for(JSONObject item:devices){
      String name=item.optString("name");
      String model=item.optString("model");
      String serialNo=item.optString("serial");
      String registry=item.optString("registry");
      String date=item.optString("date");
      Button b=new Button(this);b.setTextSize(13);b.setAllCaps(false);
      b.setText(name+"\n"+model+" • зав. № "+serialNo+"\nГосреестр: "+registry+" • "+date);
      body.addView(b);
      b.setOnClickListener(v->{
        page("Сведения о СИ");
        body.addView(label(name,18));
        body.addView(label("Методика: "+method+"\nМодификация: "+model+"\nЗаводской №: "+serialNo+"\nГосреестр: "+registry+"\nДата поверки: "+date,15));
        button("Открыть сведения на Google Диске",()->{
          Intent in=new Intent(Intent.ACTION_VIEW,Uri.parse(item.optString("url")));
          startActivity(in);
        });
        button("К списку методик",this::methodScreen);
      });
    }
    button("К списку методик",this::methodScreen);
  }
  private void searchScreen(){
    page("Методики СИ");body.addView(label("Поиск поверки СИ",18));
    addFieldLabel("Заводской номер");serial=input("Введите заводской номер",pref("serial",""));
    addFieldLabel("Поверитель");verifier=input("Название организации",pref("verifier","МЕТРОКАЛИБР"));
    verifier.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){
        getPreferences(0).edit().putString("verifier",s.toString()).apply();
      }
      public void afterTextChanged(Editable e){}
    });
    addFieldLabel("Дата поверки с");from=dateInput("from","01.01.26");
    addFieldLabel("Дата поверки по");to=dateInput("to",today());
    button("Найти в Аршине",this::search);
    matches=new LinearLayout(this);matches.setOrientation(1);body.addView(matches);
  }
  private static String pick(JSONObject j,String... keys){for(String k:keys){String x=j.optString(k,"");if(!x.isEmpty()&&!x.equals("null"))return x;}return "";}
  private static String enc(String s){try{return java.net.URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
  private String get(String url)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("Accept","application/json");
    try{if(c.getResponseCode()!=200)throw new IOException("Ответ Аршина HTTP "+c.getResponseCode());
      ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>7000000)throw new IOException("Ответ слишком большой");}}
      return out.toString("UTF-8");
    }finally{c.disconnect();}
  }
  private JSONArray list(JSONObject j){JSONArray a=j.optJSONArray("items");if(a!=null)return a;Object o=j.opt("result");if(o instanceof JSONArray)return (JSONArray)o;if(o instanceof JSONObject){JSONObject r=(JSONObject)o;a=r.optJSONArray("items");if(a!=null)return a;return r.optJSONArray("data");}return j.optJSONArray("data");}
  private String date(String raw){if(raw.matches("\\d{2}\\.\\d{2}\\.\\d{4}.*"))return raw.substring(6,10)+"-"+raw.substring(3,5)+"-"+raw.substring(0,2);return raw.length()>=10?raw.substring(0,10):raw;}
  private void search(){
    String n=serial.getText().toString().trim(),org=verifier.getText().toString().trim(),a=isoDate(from.getText().toString()),b=isoDate(to.getText().toString());
    if(n.isEmpty()){info("Введите заводской номер");return;}if(a==null||b==null||a.compareTo(b)>0){info("Проверьте диапазон дат");return;}
    getPreferences(0).edit().putString("serial",n).putString("verifier",org).putString("from",from.getText().toString()).putString("to",to.getText().toString()).apply();
    matches.removeAllViews();records.clear();info("Ищу поверки...");
    pool.execute(()->{try{boolean truncated=false;
      for(int p=0;p<10;p++){
        JSONObject response=new JSONObject(get(API+"?mi_number="+enc(n)+"&start="+(p*100)+"&rows=100"));
        JSONArray arr=list(response);if(arr==null)throw new IOException("Неизвестная структура ответа");
        for(int i=0;i<arr.length();i++){JSONObject r=arr.optJSONObject(i);if(r==null)continue;
          String no=pick(r,"mi_number","mi.number");if(!no.isEmpty()&&!no.equalsIgnoreCase(n))continue;
          String d=pick(r,"verification_date");if(!d.isEmpty()&&(date(d).compareTo(a)<0||date(d).compareTo(b)>0))continue;
          if(!org.isEmpty()&&!r.toString().toLowerCase(Locale.ROOT).contains(org.toLowerCase(Locale.ROOT)))continue;
          records.add(r);
        }
        if(arr.length()<100)break;if(p==9)truncated=true;
      }
      boolean cut=truncated;runOnUiThread(()->showMatches(cut));
    }catch(Exception e){info("Ошибка поиска: "+e.getMessage());}});
  }
  private void showMatches(boolean cut){matches.removeAllViews();info("Найдено: "+records.size()+(cut?" (поиск ограничен первыми 1000 записями)":""));
    for(JSONObject r:records){String id=pick(r,"vri_id","id");Button b=new Button(this);
      b.setAllCaps(false);b.setText(pick(r,"mi_number","mi.number")+" • "+pick(r,"verification_date")+"\n"+pick(r,"mi_type","mi.mititle","mitype_title")+"\n"+pick(r,"org_title","organization")+"\nЗапись: "+id);matches.addView(b);b.setOnClickListener(v->details(id));}
  }
  private void details(String id){if(id.isEmpty()){info("У записи нет идентификатора");return;}info("Загружаю карточку...");
    pool.execute(()->{try{JSONObject o=new JSONObject(get(API+"/"+enc(id)));runOnUiThread(()->recordScreen(id,o));}catch(Exception e){info("Ошибка получения записи: "+e.getMessage());}});}
  private String fieldDeep(Object node,String... keys){
    if(node instanceof JSONObject){
      JSONObject j=(JSONObject)node;
      for(String key:keys){
        String value=j.optString(key,"");
        if(!value.isEmpty()&&!value.equals("null")&&!value.startsWith("{")&&!value.startsWith("["))return value;
      }
      Iterator<String> it=j.keys();
      while(it.hasNext()){String k=it.next();Object item=j.opt(k);if(item instanceof JSONObject||item instanceof JSONArray){String v=fieldDeep(item,keys);if(!v.isEmpty())return v;}}
    }else if(node instanceof JSONArray){
      JSONArray a=(JSONArray)node;
      for(int i=0;i<a.length();i++){String v=fieldDeep(a.opt(i),keys);if(!v.isEmpty())return v;}
    }
    return "";
  }
  private String fileSafe(String s){
    return s.replaceAll("[\\\\/:*?\"<>|\\\\p{Cntrl}]","_").trim();
  }
  private String verificationDate(String raw){
    if(raw.matches("\\d{4}-\\d{2}-\\d{2}.*"))return raw.substring(8,10)+"."+raw.substring(5,7)+"."+raw.substring(2,4);
    if(raw.matches("\\d{2}\\.\\d{2}\\.\\d{4}.*"))return raw.substring(0,6)+raw.substring(8,10);
    if(raw.matches("\\d{2}\\.\\d{2}\\.\\d{2}.*"))return raw.substring(0,8);
    return "";
  }
  private String makePdfName(JSONObject data,String recordId){
    String serialNo=fieldDeep(data,"mi_number","manufacture_num","serial_number","factory_number");
    String verified=fieldDeep(data,"verification_date","vri_date");
    for(JSONObject r:records){
      if(recordId.equals(pick(r,"vri_id","id"))){
        if(serialNo.isEmpty())serialNo=pick(r,"mi_number");
        if(verified.isEmpty())verified=pick(r,"verification_date");
        break;
      }
    }
    String date=verificationDate(verified);
    if(serialNo.isEmpty()||date.isEmpty())return null;
    int next=getPreferences(0).getInt("pdf_sequence",0)+1;
    return String.format(Locale.US,"%03d_%s_%s.pdf",next,fileSafe(serialNo),date);
  }
  private void recordScreen(String id,JSONObject data){page("Сведения о поверке");body.addView(label("Запись ФИФ: "+id,16));
    ArrayList<String> lines=new ArrayList<>();flatten("",data,lines,0);
    LinearLayout saveButtons=new LinearLayout(this);
    saveButtons.setOrientation(LinearLayout.HORIZONTAL);
    saveButtons.setGravity(Gravity.CENTER_VERTICAL);
    body.addView(saveButtons,new LinearLayout.LayoutParams(-1,-2));
    Button saveHere=new Button(this);
    saveHere.setText("Сохр. сюда");
    saveHere.setAllCaps(false);
    saveHere.setTextSize(12);
    saveHere.setMinHeight(dp(44));
    saveHere.setPadding(dp(4),0,dp(4),0);
    LinearLayout.LayoutParams leftParams=new LinearLayout.LayoutParams(0,dp(48),1);
    leftParams.setMarginEnd(dp(4));
    saveButtons.addView(saveHere,leftParams);
    saveHere.setOnClickListener(v->savePdf(id,data,lines,false));
    Button saveGoogle=new Button(this);
    saveGoogle.setText("Сохр. Google");
    saveGoogle.setAllCaps(false);
    saveGoogle.setTextSize(12);
    saveGoogle.setMinHeight(dp(44));
    saveGoogle.setPadding(dp(4),0,dp(4),0);
    LinearLayout.LayoutParams rightParams=new LinearLayout.LayoutParams(0,dp(48),1);
    rightParams.setMarginStart(dp(4));
    saveButtons.addView(saveGoogle,rightParams);
    saveGoogle.setOnClickListener(v->savePdf(id,data,lines,true));
    for(String s:lines)body.addView(label(s,13));
    button("Вернуться к поиску",this::searchScreen);
  }
  private void flatten(String prefix,Object value,ArrayList<String> out,int depth){
    if(depth>12||out.size()>2000)return;
    if(value instanceof JSONObject){JSONObject j=(JSONObject)value;Iterator<String> it=j.keys();while(it.hasNext()){String k=it.next();flatten(prefix.isEmpty()?k:prefix+" / "+k,j.opt(k),out,depth+1);}}
    else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)flatten(prefix+" ["+(i+1)+"]",a.opt(i),out,depth+1);}
    else if(value!=null&&value!=JSONObject.NULL)out.add(prefix+": "+String.valueOf(value));
  }
  private void savePdf(String id,JSONObject data,ArrayList<String> lines,boolean selectDrive){
    try{
      currentName=makePdfName(data,id);
      if(currentName==null){info("Не удалось получить заводской номер или дату поверки из Аршина. PDF не сохранён.");return;}
      ArrayList<String> all=new ArrayList<>();
      all.add("Сведения о результатах поверки СИ");
      all.add("Источник: ФГИС Аршин");
      all.add("Номер записи: "+id);
      all.add("https://fgis.gost.ru/fundmetrology/eapi/vri/"+id);
      all.add("");all.addAll(lines);
      currentPdf=pdf(all);
      if(selectDrive){chooseSave();info("Выберите Google Диск и нужную папку.");return;}
      if(folder==null){chooseSave();return;}
      try{
        String docId=DocumentsContract.getTreeDocumentId(folder);
        Uri parent=DocumentsContract.buildDocumentUriUsingTree(folder,docId);
        Uri dest=DocumentsContract.createDocument(getContentResolver(),parent,"application/pdf",currentName);
        if(dest==null)throw new IOException("Нет доступа");
        write(dest);
        info("PDF сохранён: "+currentName);
      }catch(Exception e){info("Папка недоступна. Выберите место сохранения.");chooseSave();}
    }catch(Exception e){info("Ошибка PDF: "+e.getMessage());}
  }
  private void write(Uri uri)throws Exception{
    try(OutputStream o=getContentResolver().openOutputStream(uri,"w")){
      if(o==null)throw new IOException("Нет доступа к файлу");
      o.write(currentPdf);
    }
    getPreferences(0).edit().putString("last",currentName)
      .putInt("pdf_sequence",getPreferences(0).getInt("pdf_sequence",0)+1).apply();
  }
  private void chooseSave(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/pdf");i.putExtra(Intent.EXTRA_TITLE,currentName);startActivityForResult(i,PICK_SAVE);}
  private byte[] pdf(ArrayList<String> raw)throws Exception{
    final int w=595,h=842,margin=38;Paint p=new Paint(3);p.setTypeface(Typeface.create("sans-serif",0));p.setTextSize(11);p.setColor(0xff222222);
    ArrayList<String> lines=new ArrayList<>();for(String s:raw){if(s.isEmpty()){lines.add("");continue;}String rem=s;while(!rem.isEmpty()){int len=p.breakText(rem,true,w-2*margin,null);if(len<=0)len=1;lines.add(rem.substring(0,len));rem=rem.substring(len);}}
    PdfDocument doc=new PdfDocument();int at=0,page=1;do{PdfDocument.Page pg=doc.startPage(new PdfDocument.PageInfo.Builder(w,h,page).create());Canvas canvas=pg.getCanvas();int y=margin+12;
      while(at<lines.size()&&y<h-margin){canvas.drawText(lines.get(at++),margin,y,p);y+=16;}canvas.drawText("ФГИС Аршин • "+page,margin,h-20,p);doc.finishPage(pg);page++;
    }while(at<lines.size());ByteArrayOutputStream out=new ByteArrayOutputStream();doc.writeTo(out);doc.close();return out.toByteArray();
  }
  private void settings(){page("Настройки");body.addView(label("Установленная версия: "+BuildConfig.VERSION_NAME,15));body.addView(label("Формат: PDF",17));
    body.addView(label("Локальная папка: Протоколы поверки СИ",16));body.addView(label(folder==null?"Выберите папку один раз":"Локальная папка подключена",14));
    button("Выбрать папку на смартфоне",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK_FOLDER);});
    body.addView(label("Google Диск: при просмотре поверки нажмите «Сохранить PDF на Google Диск» и выберите папку через системное окно. Автоматическая синхронизация ещё не реализована.",15));
    button("Открыть Google Диск",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://drive.google.com/drive/u/0/my-drive"))));
  }
  private void files(){page("Сохранённые PDF");body.addView(label("Последний PDF: "+pref("last","ещё нет"),15));body.addView(label("Полная история и синхронизация с Google Диском — следующие этапы разработки.",14));}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
    try{if(request==PICK_FOLDER){Uri u=data.getData();getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);folder=u;getPreferences(0).edit().putString("folder",u.toString()).apply();settings();}
      else if(request==PICK_SAVE&&currentPdf!=null){write(data.getData());info("PDF сохранён: "+currentName);}}
    catch(Exception e){info("Ошибка сохранения: "+e.getMessage());}
  }
  @Override protected void onDestroy(){pool.shutdownNow();super.onDestroy();}
}
