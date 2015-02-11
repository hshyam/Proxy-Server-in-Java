
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Main {

    public static void main(String args[]) throws IOException {
		for (int i = 0; i<args.length; i++)
			System.out.println(i +"\t"+args[i]);
        if (args.length != 2) {
            throw new RuntimeException("Syntax: proxy_server.ProxyServer [port number > 50000]");
        }
        int serverPortNum = Integer.parseInt(args[1]);
        if (serverPortNum <= 50000) {
            throw new RuntimeException("Syntax: proxy_server.ProxyServer [port number > 50000]");
        }

        String configFile = args[0];

//        int serverPortNum = 50000;
//        String configFile = "assignment7/proxy_server/config.txt";

        //read config from file
        HashMap<String, ArrayList<String>> blockedContent = new HashMap<String, ArrayList<String>>();

        try{
            //use scanner to read file
            Scanner scanner = new Scanner(new File(configFile));
            while(scanner.hasNext()){
                String line = scanner.nextLine();
                //ignore comments
                if(line.startsWith("#") || line.isEmpty())
                    continue;

                String[] params = line.split(" ");
                //read mime types from host
                ArrayList<String> mimeTypes = new ArrayList<String>();
                if(params.length > 1)
                    for(int i = 1; i<params.length; i++)
                        mimeTypes.add(params[i]);

                blockedContent.put(params[0], mimeTypes);
				System.out.println("Blocking :" + params[0] +" "+ mimeTypes);
            }

        }
            catch(Exception e){
            e.printStackTrace();
        }

        //init server socket
        ServerSocket server = new ServerSocket(serverPortNum);

        //init logger
        String logFile = "assignment7/proxy_server/logfile";
        FileHandler fileHandler = new FileHandler("application_log.txt", serverPortNum, 7, false);
        fileHandler.setFormatter(new SimpleFormatter());
        Logger logger = Logger.getGlobal();
        logger.addHandler(fileHandler);

        //wait for client (browser)
        while (true) {
            Socket client = server.accept();
            //init new thread
			System.out.println("Starting new connection");
            ProxyServer c = new ProxyServer(client, blockedContent, logger);
            c.start();
        }
    }
}
