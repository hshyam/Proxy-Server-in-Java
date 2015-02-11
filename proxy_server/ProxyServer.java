
import java.net.*;
import java.io.*;
import java.text.DateFormat;
import java.util.*;
import java.util.logging.Logger;

public class ProxyServer extends Thread {
    //cache folder path
    private static final String cacheFolder = "CacheFolder";
    //socket with client (browser)
    protected Socket serverSocket = null;
    //input and output stream with browser
    InputStream streamFromBrowser = null;
    OutputStream streamToBrowser = null;

    //request params
    String request = "";
    String uri = "";
    String protocol = "";

    //request headers
    HashMap<String, String> requestHeaders = null;
    //info from config.txt
    HashMap<String, ArrayList<String>> blockedContent;
    //store original request
    byte[] requestArray;
    //store original request as string
    String requestString;
    //logger
    Logger logger;

    //constructor
    ProxyServer(Socket aSocket, HashMap<String, ArrayList<String>> blockedContent, Logger logger) {

        this.serverSocket = aSocket;
        this.blockedContent = blockedContent;
        this.logger = logger;
    }

    /* this is the thread that is started when a new instance is invoked */
    @Override
    public void run() {

        try {
            //init streams
            streamFromBrowser = serverSocket.getInputStream();
            streamToBrowser = serverSocket.getOutputStream();
            //read request
            requestArray = new byte[1024];
            int requestLength = streamFromBrowser.read(requestArray);

            requestString = new String(requestArray);

        } catch (IOException ex) {
            ex.printStackTrace();
            if (streamFromBrowser == null || streamToBrowser == null) {
                return;
            }
        }
        //parse request. first get lines
        String[] lines = requestString.split("\r\n");

        /* check if the request method is supported */
        if(!lines[0].startsWith("GET") && !lines[0].startsWith("HEAD")){

            System.err.println("Unsupported Operation");
			System.err.println(lines[0]);
			System.err.println(lines[1]);

        }else{
			
            //get params of first line
            String[] firstParams = lines[0].split(" ");
            request = firstParams[0];  
            uri = firstParams[1];  
            protocol = firstParams[2];
			
            //get headers
            requestHeaders = getRequestHeaders(lines);
			
            //check host
            if(blockedContent.containsKey(requestHeaders.get("Host"))){
				String content = blockedContent.get(requestHeaders.get("Host")).toString();
				System.out.println("Blocking "+content);
				if (content.equals("[*]")) {
                logger.info(requestHeaders.get("Host")+"::blocked");

                sendError("HTTP/1.1 403 Forbidden", "Blocked resource");

                try {
                    serverSocket.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
                return;
				}
            }

            //generate file path for request
            String filePath = getFilePath();
            if(filePath == null) return;

            filePath = filePath.replaceAll("/", "_");

            String resourcePath;
            if (filePath.equals("/") || filePath.equals("")) {
               resourcePath = cacheFolder+"/" + filePath+"index.html";
            } else {
               resourcePath = cacheFolder+"/" + filePath;
            }
		
            File file = new File(resourcePath);
            //if file exists - get it from chache
            if(file.exists()){
                getFileFromCache(file, true);
            }else{
                //else create file and get it from original server
                try {
                    file.createNewFile();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }

                getFromServer(file);
            }
        }

        try {
            serverSocket.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Get file from cahce. Also check cache-control max-age
     * @param file
     * @param checkMaxAge
     */
    private void getFileFromCache(File file, boolean checkMaxAge){

        try{
			System.out.println("Checking cache for file: " + file );
            if(checkMaxAge){
                //if file isn't valid
                if(checkFileForMaxAge(file)){
                    //read it from server
                    getFromServer(file);
                }else
                    //else - read from cahce
                    getFileFromCache(file, false);
            }else{
				
                //open file stream
                FileInputStream fileInputStream = new FileInputStream(file);

                byte[] bytes = new byte[1024];
                int bytesRead = 0;

                //write file to browser stream
                while ((bytesRead = fileInputStream.read(bytes)) != -1) {
                    streamToBrowser.write(bytes, 0, bytesRead);
                }

                logger.info(uri+"::served from cache");

                streamToBrowser.flush();
                fileInputStream.close();
            }
        }catch(Exception e){
            e.printStackTrace();
        }
    }


    private boolean checkFileForMaxAge(File file){

        try{

            String rez = "";
            //get file
            FileInputStream fileInputStream = new FileInputStream(file);

            byte[] bytes = new byte[1024];
            int bytesRead = 0;

            while ((bytesRead = fileInputStream.read(bytes)) != -1) {
                rez += new String(bytes);
            }

            fileInputStream.close();
            //parse file
            String[] parts = rez.split("\r\n\r\n");
            String[] headers = parts[0].split("\r\n");
            //get headers
            HashMap<String, String> map = getRequestHeaders(headers);
            //if cahce-control undefined - return false and load file from cahce
            if(!map.containsKey("Cache-Control")){
                return false;
            }

            String cache_control = map.get("Cache-Control");

            String[] cache_control_parts = cache_control.split(";");

            cache_control = "";
            //get max-age value
            for(String s : cache_control_parts){
                if(s.indexOf("max-age") != -1){
                    cache_control = s;
                    break;
                }
            }

            if(cache_control.isEmpty()){
                return true;
            }

            int seconds = Integer.parseInt(cache_control.split("=")[1]);
            //get last modif value
            String If_Modified_Since = map.get("If-Modified-Since");
            if(If_Modified_Since == null)
                //if not defined - return true and load file from original server
                return true;

            //check time
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG);
            TimeZone tz = TimeZone.getTimeZone("GMT");
            df.setTimeZone(tz);
            //get time from file heades
            Date date = df.parse(If_Modified_Since);
            long miliseconds = date.getTime();

            //get current time
            long currentTime = System.currentTimeMillis();

            //if validity time left
            if(currentTime-miliseconds > seconds*1000)
                //return true and load file from origin server
                return true;
            else
            //else - get file from cahce
                return false;

        }catch(Exception e){
            e.printStackTrace();
        }

        return true;
    }


    private void getFromServer(File file){

        byte[] reply = new byte[4096];
        Socket server = null;

        try {
            //get host and port from uri
            URL url = new URL(uri);
            String host = url.getHost();
            int port = url.getPort();
            if(port == -1)
                port = 80;

            //init socket with origin server
            server = new Socket(host, port);
            //init streams with origin server
            final InputStream streamFromServer = server.getInputStream();
            final OutputStream streamToServer = server.getOutputStream();

            //asynchronously in separate thread, send request to origin server
            Thread t = new Thread() {
                public void run() {
                    try {
                        streamToServer.write(requestArray, 0, requestArray.length);
                        streamToServer.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            };

            t.start();

            //init stream for writing to cache
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            String rez = "";

            int bytesRead;
            try {
                while ((bytesRead = streamFromServer.read(reply)) != -1) {
                    //read response from origin server
                    fileOutputStream.write(reply, 0, bytesRead);
                    rez += new String(reply);
                }

                fileOutputStream.flush();
                fileOutputStream.close();
				
                //check file mime type
                if(isContentAllowed(new String(rez), host)){
                    
                     //if file mime type not blocked - send to browser file from cahce
                    logger.info(uri+"::contacted origin server");
                    logger.info(uri+"::"+file.getPath());
                    getFileFromCache(file, false);

                }
                    else{
                    //else delete file and send to browser error
                    file.delete();
                    sendError("HTTP/1.1 403 Forbidden", "Blocked resource");
                    logger.info(uri+"::File type not allowed");
                }

            } catch (IOException e) {
                e.printStackTrace();
            }

        } catch (Exception e) {
            System.err.println(e);
        } finally {
            try {
                if (server != null)
                    server.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private boolean isContentAllowed(String rez, String host){
			
        //parse header
        String[] parts = rez.split("\r\n\r\n");
        String[] headers = parts[0].split("\r\n");
        
		
        HashMap<String, String> map = getRequestHeaders(headers);
        String contentType = map.get("Content-Type");

        //check type
        if(blockedContent.containsKey(host)){
            ArrayList<String> types = blockedContent.get(host);

            for(String type : types){
			

                if(contentType.contains(type))
                    return false;

                String[] typeParts = type.split("/");

                if(type.contains(typeParts[0]) && typeParts[1].equals("*"))
                    return false;
            }
        }

        return true;
    }

    private String getFilePath(){

        URL resourceURL = null;
        String filePath;
        if (uri.startsWith("http")) {
            try {
                resourceURL = new URL(uri); 
            } catch (MalformedURLException ex) {
                ex.printStackTrace();
                if (resourceURL == null) {
                    return null;
                }
            }
            filePath = resourceURL.getHost()+"_"+resourceURL.getPath();
        } else {
            filePath = uri;
        }

        return filePath;
    }

    private HashMap<String, String> getRequestHeaders(String[] array){

        /* here we handle the remaining headers regardless of the order */
        HashMap<String, String> requestHeaders = new HashMap<String, String>();

        for(int i = 1; i<array.length; i++){

            if(array[i].isEmpty())
                break;
			System.out.println(array[i]);
			
							
            StringTokenizer stringTokenizer = new StringTokenizer(array[i]); //parses header line
            String requestHeaderKey = stringTokenizer.nextToken(":").trim();
            String requestHeaderValue = array[i].replaceFirst(requestHeaderKey, "").replaceFirst(":", "").trim();
			if (i==1 && !requestHeaderValue.contains("www."))
				requestHeaderValue = "www.".concat(requestHeaderValue);

            requestHeaders.put(requestHeaderKey, requestHeaderValue); //use this later but add it to hashmap
        }

        return requestHeaders;
    }

    public void sendError(String error, String message) {

        //init GMT time
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG);
        TimeZone tz = TimeZone.getTimeZone("GMT");
        df.setTimeZone(tz);

        //create response
        String rez = error;
        rez += "Date: " + df.format(new Date())+"\r\n";
        rez += "Content-Type: text/plain; charset=UTF-8"+"\r\n";
        rez += "Accept-Ranges: bytes"+"\r\n";
        rez += "Connection: close"+"\r\n"+"\r\n";
        rez += message;

        //send response
        try{
            streamToBrowser.write(rez.getBytes(), 0, rez.getBytes().length);
            streamToBrowser.flush();
        }catch(Exception e){
            e.printStackTrace();
        }
    }
}
