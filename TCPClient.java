import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;


public class TCPClient {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    public void connect(String serverAddress, int port) throws IOException {
        this.socket = new Socket("172.30.248.184", port);
        this.out = new PrintWriter(this.socket.getOutputStream(), true);
        this.in = new BufferedReader(new InputStreamReader(this.socket.getInputStream()));
        System.out.println("Connected to server at " + serverAddress + ":" + port);
    }

    public void sendMessage(String message) throws IOException {
        this.out.println(message);
        String response = this.in.readLine();
        System.out.println("Received: " + response);
    }

    public void disconnect() throws IOException {
        if (this.in != null) {
            this.in.close();
        }

        if (this.out != null) {
            this.out.close();
        }

        if (this.socket != null) {
            this.socket.close();
        }

    }

    public static void main(String[] args) {

        if (args.length == 5)
        {
            String hostName = args[0];
            int port = Integer.parseInt(args[1]);
            String command = args[2];
            String username = args[3];
            String password = args[4];
            String data = command + " " + username + " " + password;
        }

        if (args.length == 4)
        {
            String hostName = args[0];
            int port = Integer.parseInt(args[1]);
            String command = args[2];
            String content = args[3];
        }

        // int port = Integer.parseInt(args[args.length- 1]); //The last part of the argument should be the message

        StringBuilder messageBuilder = new StringBuilder();

        for (int i = 1; i < args.length-1; i++){
            
            messageBuilder.append(args[i]);
            if (i < args.length - 2){
                messageBuilder.append(" ");
            }
        }

        // String message = messageBuilder.toString();

        TCPClient client = new TCPClient();

        Scanner scanner = new Scanner(System.in);

        try {
            client.connect(hostName, port);

            client.sendMessage(data);
            
           /// String userInput;
          //  while(!(userInput = scanner.nextLine()).equals("bye")) {
          //      client.sendMessage(userInput);
          //  }

          //  client.sendMessage(message);
            client.disconnect();

            
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            scanner.close();
        }

    }
}